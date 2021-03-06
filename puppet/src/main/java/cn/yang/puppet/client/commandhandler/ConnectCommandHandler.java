package cn.yang.puppet.client.commandhandler;

import cn.yang.common.dto.Request;
import cn.yang.common.dto.Response;
import cn.yang.common.command.Commands;
import cn.yang.common.util.PropertiesUtil;
import cn.yang.puppet.client.constant.ConfigConstants;
import cn.yang.common.exception.CommandHandlerException;
import cn.yang.puppet.client.constant.MessageConstants;
import cn.yang.puppet.client.exception.HeartBeatException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.ScheduledFuture;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author Cool-Coding
 *         2018/7/27
 */
public class ConnectCommandHandler extends AbstractPuppetCommandHandler {

    private String host;
    private int port;
    private HeartBeatAndScreenSnapShotTaskManagement taskManagement;

    public ConnectCommandHandler() throws CommandHandlerException{
        try {
            host = PropertiesUtil.getString(ConfigConstants.CONFIG_FILE_PATH, ConfigConstants.SERVER_IP);
            port = PropertiesUtil.getInt(ConfigConstants.CONFIG_FILE_PATH, ConfigConstants.SERVER_PORT);
        }catch (IOException e){
            throw new CommandHandlerException(e.getMessage(),e);
        }
    }

    @Override
    protected void handle0(ChannelHandlerContext ctx, Response response) throws Exception {
        if(StringUtils.isEmpty(getPuppetName())){
            setPuppetName(response.getPuppetName());
            info(response,MessageConstants.PUPPET_NAME_FROM_SERVER,response.getPuppetName());
            popMessageDialog(MessageConstants.PUPPET_NAME_FROM_SERVER,response.getPuppetName());
        }

        reset();

        try {
            //为减少带宽负载，发送屏幕截图时不再发送心跳，当屏幕截图发送完后，继续发送心跳，
            //启动一个线程，进行周期性检查，管理心跳与屏幕截图任务
            taskManagement = new HeartBeatAndScreenSnapShotTaskManagement(ctx, response);
            int interval = PropertiesUtil.getInt(ConfigConstants.CONFIG_FILE_PATH, ConfigConstants.TASK_CHECK_INTERVAL);
            ctx.executor().scheduleAtFixedRate(() -> {
                try {
                    taskManagement.check();
                } catch (IOException e) {
                    error(response, e.getMessage());
                    throw new RuntimeException(e);
                }
            }, 0, interval, TimeUnit.MILLISECONDS);

        }catch (IOException e){
            ctx.executor().shutdownGracefully();
            throw new HeartBeatException(e.getMessage(),e);
        }
    }

    private void reset(){
        //连接时，由于重新生成puppetName,重置为发送心跳状态，并且如果之前有任务的话，则关闭
        stopUnderControlled();
        if (taskManagement!=null){
            taskManagement.reset();
        }
    }

    private class HeartBeatAndScreenSnapShotTaskManagement {
        private final Map<Runnable,ScheduledFuture> tasks=new HashMap<>();
        private ChannelHandlerContext ctx;
        private Response response;

        private HeartBeatAndScreenSnapShotTaskManagement(ChannelHandlerContext ctx, Response response){
            this.ctx=ctx;
            this.response=response;
        }

        private void reset(){
            if (tasks.size()>0){
             for(ScheduledFuture task:tasks.values()){
                 task.cancel(true);
              }
            }
        }

        private void check() throws IOException{
            if(isUnderControlled()){
                if(tasks.get(heartBeatTask)!=null && !tasks.get(heartBeatTask).isCancelled()){
                    tasks.get(heartBeatTask).cancel(true);
                }

                if (tasks.get(screenSnapShotTask)==null || tasks.get(screenSnapShotTask).isCancelled()){
                    tasks.put(screenSnapShotTask,startScreenSnapShotTask());
                }
            }else{
                if(tasks.get(screenSnapShotTask)!=null && !tasks.get(screenSnapShotTask).isCancelled()){
                    tasks.get(screenSnapShotTask).cancel(true);
                }

                if (tasks.get(heartBeatTask)==null || tasks.get(heartBeatTask).isCancelled()){
                    tasks.put(heartBeatTask,startHeartBeatTask());
                }
            }
        }

        /**
         * 开始发送屏幕截图
         * @return 返回任务执行器
         * @throws IOException 读取配置文件异常
         */
        private ScheduledFuture<?> startScreenSnapShotTask() throws IOException{
            int interval = PropertiesUtil.getInt(ConfigConstants.CONFIG_FILE_PATH, ConfigConstants.SCREEN_REFRESH_FREQUENCY);
            return ctx.executor().scheduleAtFixedRate(screenSnapShotTask, 0, interval, TimeUnit.MILLISECONDS);
        }


        /**
         * 开始发送心跳
         * @throws IOException  读取配置文件异常
         * @return 返回任务执行器
         */
        private ScheduledFuture<?> startHeartBeatTask() throws IOException{
            int interval = PropertiesUtil.getInt(ConfigConstants.CONFIG_FILE_PATH, ConfigConstants.HEARTBEAT_INTERVAL);
            return ctx.executor().scheduleAtFixedRate(heartBeatTask, 0, interval, TimeUnit.MILLISECONDS);
        }

        /**
         * 心跳任务
         */
        private Runnable heartBeatTask = new Runnable() {

            @Override
            public void run() {
                Request heartBeatRequest = AbstractPuppetCommandHandler.buildRequest(Commands.HEARTBEAT,null);
                debug(response, MessageConstants.SEND_A_HEARTBEAT, host, String.valueOf(port));

                if (heartBeatRequest != null) {
                    if (ctx.channel() != null && ctx.channel().isOpen()) {
                        ctx.writeAndFlush(heartBeatRequest);
                    }
                }
            }
        };

        /**
         * 屏幕截图任务
         */
        private Runnable screenSnapShotTask = new Runnable(){
            private byte[] previousScreen;

            @Override
            public void run() {
                final byte[] bytes = REPLAY.getScreenSnapshot();
                if (isDifferentFrom(bytes)) {
                    final Request request = buildRequest(Commands.SCREEN, bytes);
                    debug(response, MessageConstants.SEND_A_SCREENSNAPSHOT, host, String.valueOf(port));
                    if (request != null) {
                        if (ctx.channel() != null && ctx.channel().isOpen()) {
                            ctx.writeAndFlush(request);
                        }
                    }
                }
            }



            /**
             * 比较上一个屏幕与当前屏幕是否一样
             * @param now
             * @return
             */
            private boolean isDifferentFrom(byte[] now){
                if (now == null){
                    return false;
                }

                //如果前一个屏幕为空，而且当前屏幕与前一个屏幕不一样，则发送
               if(previousScreen==null || previousScreen.length == 0 ||  previousScreen.length != now.length){
                   previousScreen = now;
                   return true;
               }

               int len=previousScreen.length;
               boolean changeable = false;
               for(int i=0;i<len;i++){
                if(previousScreen[i] != now[i] ){
                    previousScreen = now;
                    changeable = true;
                    break;
                }
               }
                return changeable;
            }
        };
    }
}
