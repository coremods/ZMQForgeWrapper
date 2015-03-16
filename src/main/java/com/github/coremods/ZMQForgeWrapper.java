package com.github.coremods;

import java.nio.charset.Charset;
import java.util.Random;

import javax.annotation.PreDestroy;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mdkt.compiler.InMemoryJavaCompiler;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.PollItem;
import org.zeromq.ZMQ.Socket;
import org.zeromq.ZMsg;
import org.zeromq.ZMQ.Poller;

import com.google.gson.Gson;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.config.Configuration;
import cpw.mods.fml.common.IWorldGenerator;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent;
import cpw.mods.fml.common.registry.GameRegistry;

@Mod(modid = "ZMQForgeWrapper", name = "ZMQForgeWrapper", version = "0.1.0")
public class ZMQForgeWrapper {

  private static final Charset CHARSET = Charset.forName("UTF-8");

  private static final Logger logger = LogManager.getLogger(ZMQForgeWrapper.class);

  private static final String DEFAULT_ZMQ_ADDRESS = "tcp://*:5570";

  @Instance(value = "ZMQForgeWrapper")
  public static ZMQForgeWrapper instance;

  @EventHandler
  public void preInit(FMLPreInitializationEvent event) {
    Configuration config = new Configuration(event.getSuggestedConfigurationFile());

    config.load();
    zmqAddress =
        config.get("ZMQForgeWrapper", "zmqAddress", DEFAULT_ZMQ_ADDRESS, "ZeroMQ bind address")
            .getString();
    config.save();
  }

  @EventHandler
  public void load(FMLInitializationEvent event) {
    zmqContext = new ZContext();

    zmqServerSocket = zmqContext.createSocket(ZMQ.ROUTER);
    zmqServerSocket.bind(zmqAddress);

    gson = new Gson();

  }

  @EventHandler
  public void stopping(FMLServerStoppingEvent event) {
    zmqServerSocket.close();
    zmqContext.close();
  }

  private ZContext zmqContext;
  private Socket zmqServerSocket;
  private String zmqAddress;
  private Gson gson;

  @SubscribeEvent
  public void servertick(ServerTickEvent event) {

    ZMsg msg;
    while ((msg = ZMsg.recvMsg(zmqServerSocket, ZMQ.DONTWAIT)) != null) {
      ZFrame address = msg.pop();
      ZFrame content = msg.pop();
      assert (content != null);
      msg.destroy();

      byte[] data = content.getData();
      String req = new String(data);
      logger.info("server got request: " + req);

      StringBuilder sourceCode = new StringBuilder();
      sourceCode.append("public class Execution { public Object call() { \n");
      sourceCode.append(req);
      sourceCode.append("\n}}");

      try {
        Class<?> helloClass = InMemoryJavaCompiler.compile("Execution", sourceCode.toString());
        Object result = helloClass.getMethod("call").invoke(helloClass.newInstance());

        String replyString = req + " == " + gson.toJson(result);
        ZFrame reply = new ZFrame(replyString.getBytes(CHARSET));
        address.send(zmqServerSocket, ZFrame.REUSE + ZFrame.MORE);
        reply.send(zmqServerSocket, ZFrame.REUSE);
        reply.destroy();

      } catch (Exception e) {
        logger.warn(e, e);
      }

      address.destroy();
      content.destroy();

    }

  }
}
