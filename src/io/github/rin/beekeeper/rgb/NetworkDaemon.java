package io.github.rin.beekeeper.rgb;

import java.io.*;
import java.net.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import android.net.wifi.WifiManager;
import android.content.Context;

/** Root app_process worker launched by Magisk; receives one RGB24 universe/frame. */
public final class NetworkDaemon {
    static final String BASE="/sys/class/led_i2c/led_i2c/";
    private final Object mutex=new Object();
    private final Map<String,Source> sources=new HashMap<>();
    private volatile Config config=new Config(Collections.emptyMap());
    private volatile boolean portsOpen;
    private volatile DatagramSocket sacnSocket,ddpSocket;
    private byte[] pending,ddpBuffer;
    private long receivedAt,serial,rendered,accepted,invalid;
    private String protocol="",peer="",error="";
    private int ddpSequence;
    private String ddpPeer="";
    private long ddpAt;
    private boolean ownsDriver;
    private String previousEnable="1";
    private Om2Output output;
    private McuManager mcu;
    private boolean pixelMode;
    private Object hidl;
    private java.lang.reflect.Method setLed;
    private Context systemContext;
    private WifiManager.MulticastLock multicastLock;
    private static final class Source {
        int sequence,priority;long at;byte[] frame;
    }
    private static final class Config {
        final boolean enabled,sacn,ddp;
        final int count,universe,channel;
        final Map<String,String> values;
        Config(Map<String,String> v) {
            values=v;enabled=number(v,"beekeeper_net_enabled",0)==1;
            sacn=number(v,"beekeeper_net_sacn",1)==1;ddp=number(v,"beekeeper_net_ddp",1)==1;
            count=number(v,"beekeeper_net_count",0);universe=number(v,"beekeeper_net_universe",1);
            channel=number(v,"beekeeper_net_channel",1);
        }
        boolean valid() {return NetworkLimits.validCount(count)&&universe>=1&&universe<=63999&&channel>=1&&channel+count*3-1<=512&&(sacn||ddp);}
        boolean same(Config c) {return enabled==c.enabled&&sacn==c.sacn&&ddp==c.ddp&&count==c.count&&universe==c.universe&&channel==c.channel;}
    }
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=0) throw new SecurityException("Root worker required");
        RandomAccessFile lockFile=new RandomAccessFile("/data/local/tmp/beekeeper-rgb-network.lock","rw");
        FileLock lock=lockFile.getChannel().tryLock();
        if(lock==null) {lockFile.close();return;}
        NetworkDaemon daemon=new NetworkDaemon();
        daemon.output=new Om2Output(args.length==1?args[0]:"/data/adb/modules/beekeeper_rgb/bin/om2-pixel-writer");
        File module=new File(args.length==1?args[0]:"/data/adb/modules/beekeeper_rgb/bin/om2-pixel-writer").getParentFile().getParentFile();
        daemon.mcu=new McuManager(module,new McuManager.Host(){
            public String savedEnable(){try{return daemon.ownsDriver?daemon.previousEnable:new String(java.nio.file.Files.readAllBytes(new File(BASE+"enable").toPath()),StandardCharsets.US_ASCII).trim();}catch(Exception e){return "";}}
            public void pause()throws Exception {
                putSetting("beekeeper_net_enabled","0");daemon.closePorts();daemon.release();daemon.led(13,0);
                writeNode("init_params","3 0x50 0x81 0 0\n");writeNode("enable","0\n");
                try {
                    daemon.output.open(false);
                    for(int n=0;n<3;n++){daemon.output.send(new byte[]{(byte)255,0,0});Thread.sleep(250);daemon.output.send(new byte[3]);Thread.sleep(250);}
                } finally {daemon.output.close();}
            }
            public void restore(String enable)throws Exception {writeNode("init_params","3 0x50 0x81 0 0\n");writeNode("enable",enable+"\n");daemon.restoreLight();}
        });
        try {daemon.run();} finally {daemon.closePorts();daemon.release();lock.release();lockFile.close();}
    }
    private void run() throws Exception {
        long poll=0,statusAt=0;
        while(true) {
            long now=android.os.SystemClock.elapsedRealtime();
            try {
                if(now>=poll) {
                    Map<String,String> current=readSettings();mcu.poll(current);
                    int capacity=Om2Output.customFirmware()?24:1;
                    if(number(current,"beekeeper_net_capacity",0)!=capacity)putSetting("beekeeper_net_capacity",String.valueOf(capacity));
                    Config next=new Config(readSettings());
                    if(next.count!=capacity){putSetting("beekeeper_net_count",String.valueOf(capacity));next=new Config(readSettings());}
                    if(!next.same(config)) {
                        closePorts();release();config=next;
                        synchronized(mutex) {pending=null;sources.clear();ddpBuffer=null;receivedAt=0;rendered=serial;}
                        error="";
                    } else config=next;
                    if(config.enabled&&config.valid()&&!mcu.recoveryRequired()&&!portsOpen) openPorts();
                    if(mcu.recoveryRequired())closePorts();
                    if(!config.enabled||!config.valid()||mcu.recoveryRequired()||number(config.values,"atmosphere_light_switcher",1)==0) release();
                    poll=now+700;
                }
                byte[] frame=null;long id=0,at;
                synchronized(mutex) {at=receivedAt;if(serial!=rendered&&pending!=null){frame=pending.clone();id=serial;}}
                if(config.enabled&&config.valid()&&!mcu.recoveryRequired()&&number(config.values,"atmosphere_light_switcher",1)==1) {
                    if(frame!=null&&now-at<3000) {takeOver();writeFrame(frame);rendered=id;error="";}
                    else if(ownsDriver&&now-at>=3000) release();
                }
                if(now>=statusAt) {
                    String state=!config.enabled?"已关闭":mcu.recoveryRequired()?"MCU 刷写记录待检查，网络输出已暂停":!config.valid()?"请设置灯珠数量和通道":!error.isEmpty()?"错误："+error:
                            ownsDriver?"接收中 "+protocol+" · "+peer:"监听中 · 等待数据";
                    putSetting("beekeeper_net_status",state+" · 收到 "+accepted+" 帧");statusAt=now+2000;
                }
            } catch(Exception e) {error=e.getClass().getSimpleName()+": "+e.getMessage();System.err.println(error);try{release();}catch(Exception ignored){} }
            Thread.sleep(50); // At most 20 driver frames/s; intermediate network frames are coalesced.
        }
    }
    private void openPorts() throws Exception {
        Config c=config;
        try {
            if(c.sacn) {
                acquireMulticast();
                MulticastSocket socket=new MulticastSocket(null);socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(5568));socket.setSoTimeout(1000);
                InetAddress group=InetAddress.getByName("239.255."+(c.universe>>8)+"."+(c.universe&255));
                int joined=0;
                for(NetworkInterface iface:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if(!iface.isUp()||iface.isLoopback()||!iface.supportsMulticast())continue;
                    try {socket.joinGroup(new InetSocketAddress(group,5568),iface);joined++;}catch(Exception ignored){}
                }
                sacnSocket=socket;System.out.println("sACN UDP 5568 universe="+c.universe+" multicast interfaces="+joined);
            }
            if(c.ddp) {ddpSocket=new DatagramSocket(null);ddpSocket.setReuseAddress(false);ddpSocket.bind(new InetSocketAddress(4048));ddpSocket.setSoTimeout(1000);}
            portsOpen=true;
            if(sacnSocket!=null)listen(sacnSocket,true,c);
            if(ddpSocket!=null)listen(ddpSocket,false,c);
        } catch(Exception e) {closePorts();throw e;}
    }
    private void closePorts() {
        portsOpen=false;
        if(sacnSocket!=null)sacnSocket.close();if(ddpSocket!=null)ddpSocket.close();
        sacnSocket=null;ddpSocket=null;
        if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();
    }
    private void acquireMulticast() {
        try {
            if(systemContext==null) {
                if(android.os.Looper.myLooper()==null)android.os.Looper.prepareMainLooper();
                Class<?> activityThread=Class.forName("android.app.ActivityThread");
                Object thread=activityThread.getMethod("systemMain").invoke(null);
                systemContext=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
            }
            if(multicastLock==null) {
                WifiManager wifi=(WifiManager)systemContext.getSystemService(Context.WIFI_SERVICE);
                multicastLock=wifi.createMulticastLock("BeekeeperRGB-sACN");multicastLock.setReferenceCounted(false);
            }
            multicastLock.acquire();System.out.println("Wi-Fi multicast lock acquired");
        } catch(Exception e) {System.err.println("Wi-Fi multicast lock unavailable: "+e);}
    }
    private void listen(DatagramSocket socket,boolean sacn,Config c) {
        Thread thread=new Thread(()->{
            byte[] bytes=new byte[2048];DatagramPacket datagram=new DatagramPacket(bytes,bytes.length);
            while(!socket.isClosed()) {
                try {
                    datagram.setLength(bytes.length);socket.receive(datagram);
                    NetworkPacket packet=sacn?NetworkPacket.sacn(bytes,datagram.getLength()):NetworkPacket.ddp(bytes,datagram.getLength());
                    if(packet==null){invalid++;continue;}
                    accept(packet,sacn,datagram.getAddress().getHostAddress(),c);
                } catch(SocketTimeoutException ignored){}catch(Exception e){if(!socket.isClosed())System.err.println(e);}
            }
        },sacn?"RGB-sACN":"RGB-DDP");thread.setDaemon(true);thread.start();
    }
    private void accept(NetworkPacket p,boolean sacn,String address,Config c) {
        long now=android.os.SystemClock.elapsedRealtime();
        synchronized(mutex) {
            if(c!=config&&!c.same(config))return;
            if(sacn) {
                if(p.universe!=c.universe)return;
                String key=Arrays.toString(p.cid);
                sources.entrySet().removeIf(e->now-e.getValue().at>2500);
                Source source=sources.get(key);
                if(p.terminated){sources.remove(key);return;}
                if(p.data.length<c.channel-1+c.count*3)return;
                if(source!=null&&now-source.at<2500&&!NetworkPacket.newer(source.sequence,p.sequence,256))return;
                if(source==null){if(sources.size()>=8)return;source=new Source();sources.put(key,source);}
                source.sequence=p.sequence;source.priority=p.priority;source.at=now;
                source.frame=Arrays.copyOfRange(p.data,c.channel-1,c.channel-1+c.count*3);
                for(Source other:sources.values())if(other.priority>source.priority)return;
                pending=source.frame;protocol="sACN";
            } else {
                if(!address.equals(ddpPeer)||now-ddpAt>2500){ddpPeer=address;ddpSequence=0;ddpBuffer=new byte[c.count*3];}
                if(ddpBuffer==null||ddpBuffer.length!=c.count*3)ddpBuffer=new byte[c.count*3];
                if(p.sequence!=0&&ddpSequence!=0&&!NetworkPacket.newer(ddpSequence-1,p.sequence-1,15)
                        &&!p.repeatsDdpFrame(ddpSequence,ddpBuffer))return;
                if(p.offset<ddpBuffer.length)
                    System.arraycopy(p.data,0,ddpBuffer,(int)p.offset,Math.min(p.data.length,ddpBuffer.length-(int)p.offset));
                ddpAt=now;
                if(!p.push)return;
                if(p.sequence!=0)ddpSequence=p.sequence;
                pending=ddpBuffer.clone();protocol="DDP";
            }
            receivedAt=now;peer=address;serial++;accepted++;
        }
    }
    private void takeOver() throws Exception {
        if(ownsDriver)return;
        pixelMode=Om2Output.customFirmware();
        if(pixelMode)output.checkFirmware();
        previousEnable=new String(java.nio.file.Files.readAllBytes(new File(BASE+"enable").toPath()),StandardCharsets.US_ASCII).trim();
        if(!previousEnable.equals("0")&&!previousEnable.equals("1"))throw new IOException("无法保存驱动状态");
        ownsDriver=true;
        try {led(13,0);writeNode("init_params","3 0x50 0x81 0 0\n");writeNode("enable","0\n");output.open(pixelMode);}
        catch(Exception e){try{release();}catch(Exception restore){e.addSuppressed(restore);}throw e;}
    }
    private void writeFrame(byte[] frame) throws Exception {
        output.send(frame);
    }
    private void release() throws Exception {
        if(!ownsDriver)return;
        output.close();
        writeNode("init_params","3 0x50 0x81 0 0\n");
        writeNode("enable",previousEnable+"\n");restoreLight();ownsDriver=false;
    }
    private void restoreLight() throws Exception {
        Map<String,String> v=readSettings();
        int mode=number(v,"atmosphere_light_switcher",1)==1?number(v,"atmosphere_light_effect",0)+1:0;
        int color=number(v,"atmosphere_light_preset_color",0)+1;
        if(number(v,"atmosphere_light_same_color",0)==1&&mode!=0)mode=5;
        if((mode==1||mode==2)&&number(v,"beekeeper_rgb_enabled",0)==1)
            color=RgbColor.encode(RgbColor.parse(v.get("beekeeper_rgb_hex")));
        led(mode,color);
    }
    private void led(int mode,int color) throws Exception {
        if(hidl==null) {
            Class<?> wrapper=Class.forName("com.mitv.mtvapiclientwrapper.MiTvApiManagerProxy");
            Object manager=wrapper.getMethod("getInstance").invoke(null);
            hidl=wrapper.getMethod("getMiTvFApiSysCfg").invoke(manager);
            if(hidl==null)throw new IOException("灯控服务不可用");
            setLed=Class.forName("vendor.mi.hardware.bspmserver.V2_0.IVendorConfig").getMethod("SetColorfulLed",int.class,int.class);
        }
        Object result=setLed.invoke(hidl,mode,color);
        if(result instanceof Number&&((Number)result).intValue()<0)throw new IOException("灯控扩展不支持网络接管");
    }
    static void writeNode(String name,String text) throws IOException {
        try(FileOutputStream stream=new FileOutputStream(BASE+name)){stream.write(text.getBytes(StandardCharsets.US_ASCII));}
    }
    private static int number(Map<String,String> values,String key,int fallback) {
        try{return Integer.parseInt(values.get(key));}catch(Exception e){return fallback;}
    }
    static Map<String,String> readSettings() throws Exception {
        java.lang.Process process=new ProcessBuilder("/system/bin/settings","list","global").redirectErrorStream(true).start();
        Map<String,String> result=new HashMap<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
            String line;while((line=reader.readLine())!=null){int split=line.indexOf('=');if(split>0)result.put(line.substring(0,split),line.substring(split+1));}
        }
        if(process.waitFor()!=0)throw new IOException("无法读取灯控设置");return result;
    }
    static void putSetting(String key,String value) throws Exception {
        java.lang.Process process=new ProcessBuilder("/system/bin/settings","put","global",key,value).redirectErrorStream(true).start();
        try(InputStream input=process.getInputStream()){while(input.read()!=-1){}}
        process.waitFor();
    }
}
