package com.github.coremods;

import java.nio.charset.Charset;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mdkt.compiler.InMemoryJavaCompiler;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;
import org.zeromq.ZMsg;
import com.google.gson.Gson;

import net.minecraftforge.common.config.Configuration;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent;

@Mod(modid = "ZMQForgeWrapper", name = "ZMQForgeWrapper", version = "0.1.1")
public class ZMQForgeWrapper {

  private static final Logger logger = LogManager.getLogger(ZMQForgeWrapper.class);

  private static final Charset CHARSET = Charset.forName("UTF-8");
  private static final String DEFAULT_ZMQ_ADDRESS = "tcp://*:5570";
  private static final int DEFAULT_MAX_COMMANDS_PER_TICK = 0;

  @Instance(value = "ZMQForgeWrapper")
  public static ZMQForgeWrapper instance;

  @EventHandler
  public void preInit(FMLPreInitializationEvent event) {
    Configuration config = new Configuration(event.getSuggestedConfigurationFile());

    config.load();
    zmqAddress =
        config.get("ZMQForgeWrapper", "zmqAddress", DEFAULT_ZMQ_ADDRESS, "ZeroMQ bind address")
            .getString();
    maxCommandsPerTick =
        config.get("ZMQForgeWrapper", "maxCommandsPerTick", DEFAULT_MAX_COMMANDS_PER_TICK,
            "Maximum number of commands to execute per tick (0 for unlimited)").getInt();
    config.save();
  }

  @EventHandler
  public void load(FMLInitializationEvent event) {
    zmqContext = new ZContext();

    zmqServerSocket = zmqContext.createSocket(ZMQ.ROUTER);
    zmqServerSocket.bind(zmqAddress);

    gson = new Gson();

    FMLCommonHandler.instance().bus().register(this);
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
  private int maxCommandsPerTick;

  @SubscribeEvent
  public void servertick(ServerTickEvent event) {

    long executedCommands = 0;
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

      if (maxCommandsPerTick > 0 && ++executedCommands >= maxCommandsPerTick) {
        break;
      }
    }

  }
}
