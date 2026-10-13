package com.bydspotifymanager.app;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Spotify 8.9 DEX package and portrait-request edits. */
final class Spotify89DexUtil {
    private static final String OLD_PACKAGE="com.spotify.music";
    private static final String HELPER_BASE_PACKAGE="com.spotify.musib";
    private static final String AUTO_RESUME_BASE_PACKAGE="com.spotify.musia";
    private static final LinkedHashMap<Target,Integer> ORIENTATION_TARGETS=new LinkedHashMap<>();
    static {
        ORIENTATION_TARGETS.put(new Target("Lcom/spotify/lyrics/fullscreenview/page/LyricsFullscreenPageActivity;","onCreate"),1);
        ORIENTATION_TARGETS.put(new Target("Lcom/spotify/marquee/marquee/MarqueeActivity;","onStart"),1);
        ORIENTATION_TARGETS.put(new Target("Lp/iq10;","i"),12);
    }
    static final class PatchResult { final byte[] data; final int count; PatchResult(byte[]d,int c){data=d;count=c;} }
    private static final class Target { final String cls,name; Target(String c,String n){cls=c;name=n;} @Override public boolean equals(Object o){return o instanceof Target&&cls.equals(((Target)o).cls)&&name.equals(((Target)o).name);} @Override public int hashCode(){return 31*cls.hashCode()+name.hashCode();} }

    private Spotify89DexUtil() {}

    static PatchResult patchPackage(byte[] dex, String targetPackage) throws Exception {
        if(targetPackage.length()!=OLD_PACKAGE.length())throw new IllegalArgumentException("8.9 clone package must preserve DEX string length");
        int[] offsets=stringOffsets(dex); List<Integer> indexes=new ArrayList<>();
        byte[] oldRaw=OLD_PACKAGE.getBytes(StandardCharsets.US_ASCII);
        for(int i=0;i<offsets.length;i++){
            int[] leb=readUleb(dex,offsets[i]); int start=offsets[i]+leb[1];
            if(start+oldRaw.length<dex.length&&matches(dex,start,oldRaw)&&dex[start+oldRaw.length]==0)indexes.add(i);
        }
        for(int idx:indexes){ String prev=idx>0?decodeString(dex,offsets[idx-1]):""; String next=idx+1<offsets.length?decodeString(dex,offsets[idx+1]):new String(Character.toChars(0x10ffff)); if((!prev.isEmpty()&&prev.compareTo(targetPackage)>=0)||(!next.isEmpty()&&targetPackage.compareTo(next)>=0))throw new IllegalArgumentException("8.9 DEX lexical ordering unsafe for "+targetPackage+" between "+prev+" and "+next); }
        if(indexes.isEmpty())return new PatchResult(dex,0);
        byte[] oldItem=new byte[OLD_PACKAGE.length()+2], newItem=new byte[targetPackage.length()+2]; oldItem[0]=(byte)OLD_PACKAGE.length();newItem[0]=(byte)targetPackage.length();
        System.arraycopy(oldRaw,0,oldItem,1,oldRaw.length); System.arraycopy(targetPackage.getBytes(StandardCharsets.US_ASCII),0,newItem,1,targetPackage.length());
        byte[] out=IoUtil.replaceSameLength(dex,oldItem,newItem); return new PatchResult(Arrays.equals(out,dex)?dex:DexUtil.repair(out),indexes.size());
    }

    static byte[] retargetHelperDex(byte[] dex,String targetPackage)throws Exception{
        if(targetPackage.length()!=HELPER_BASE_PACKAGE.length())throw new IllegalArgumentException("Helper package replacement length mismatch");
        byte[] out=IoUtil.replaceSameLength(dex,HELPER_BASE_PACKAGE.getBytes(StandardCharsets.US_ASCII),targetPackage.getBytes(StandardCharsets.US_ASCII));
        out=IoUtil.replaceSameLength(out,HELPER_BASE_PACKAGE.replace('.','/').getBytes(StandardCharsets.US_ASCII),targetPackage.replace('.','/').getBytes(StandardCharsets.US_ASCII));
        return DexUtil.repair(out);
    }
    static byte[] retargetAutoResumeDex(byte[] dex,String targetPackage)throws Exception{
        if(targetPackage.length()!=AUTO_RESUME_BASE_PACKAGE.length())throw new IllegalArgumentException("AutoResume package replacement length mismatch");
        byte[] out=IoUtil.replaceSameLength(dex,AUTO_RESUME_BASE_PACKAGE.getBytes(StandardCharsets.US_ASCII),targetPackage.getBytes(StandardCharsets.US_ASCII));
        out=IoUtil.replaceSameLength(out,AUTO_RESUME_BASE_PACKAGE.replace('.','/').getBytes(StandardCharsets.US_ASCII),targetPackage.replace('.','/').getBytes(StandardCharsets.US_ASCII));
        return DexUtil.repair(out);
    }

    static PatchResult patchRuntimePortraitRequests(byte[] dex)throws Exception{
        Integer methodRef=findSetRequestedOrientationMethod(dex); Map<Target,Integer> codeItems=findTargetCodeItems(dex);
        if(methodRef==null||codeItems.isEmpty())return new PatchResult(dex,0);
        byte[] out=dex.clone(); int changes=0;
        for(Map.Entry<Target,Integer> e:codeItems.entrySet()){
            int codeOff=e.getValue(); if(codeOff==0||codeOff+16>dex.length)continue; int insnsSize=i32(dex,codeOff+12); int start=codeOff+16; if(insnsSize<0||start+insnsSize*2L>dex.length)continue;
            int[] units=new int[insnsSize]; for(int i=0;i<insnsSize;i++)units[i]=u16(dex,start+2*i); int expected=ORIENTATION_TARGETS.get(e.getKey()); boolean hit=false;
            for(int i=0;i<units.length;i++){
                int u=units[i], op=u&0xff;
                if(op==0x12&&i+3<units.length){ int dest=(u>>>8)&0xf, raw=(u>>>12)&0xf, lit=(raw&8)!=0?raw-16:raw; if(lit==expected&&invokeMatches(units,i+1,dest,methodRef)){units[i]=(u&0x0fff)|0xf000;hit=true;break;} }
                else if(op==0x13&&i+4<units.length){ int dest=(u>>>8)&0xff, raw=units[i+1], lit=(raw&0x8000)!=0?raw-0x10000:raw; if(lit==expected&&invokeMatches(units,i+2,dest,methodRef)){units[i+1]=0xffff;hit=true;break;} }
                else if(op==0x14&&i+5<units.length){ int dest=(u>>>8)&0xff; long raw=(units[i+1]&0xffffL)|((units[i+2]&0xffffL)<<16); long lit=(raw&0x80000000L)!=0?raw-0x100000000L:raw; if(lit==expected&&invokeMatches(units,i+3,dest,methodRef)){units[i+1]=0xffff;units[i+2]=0xffff;hit=true;break;} }
            }
            if(hit){ for(int i=0;i<units.length;i++){out[start+2*i]=(byte)units[i];out[start+2*i+1]=(byte)(units[i]>>>8);} changes++; }
        }
        return new PatchResult(changes==0?dex:DexUtil.repair(out),changes);
    }

    private static boolean invokeMatches(int[] units,int i,int dest,int methodRef){ if(i+2>=units.length)return false; int inv=units[i],op=inv&0xff;if(units[i+1]!=methodRef)return false; if(op==0x6e){int count=(inv>>>12)&0xf,regs=units[i+2];return count==2&&((regs>>>4)&0xf)==dest;} if(op==0x74){int count=(inv>>>8)&0xff,first=units[i+2];return count==2&&first+1==dest;}return false; }

    private static Map<Target,Integer> findTargetCodeItems(byte[] d){
        Map<Target,Integer> result=new LinkedHashMap<>(); Map<Target,Integer> classString=new HashMap<>(), methodName=new HashMap<>();
        for(Target t:ORIENTATION_TARGETS.keySet()){classString.put(t,exactStringIndex(d,t.cls));methodName.put(t,exactStringIndex(d,t.name));}
        int typeSize=i32(d,64),typeOff=i32(d,68);Map<Integer,Integer> stringToType=new HashMap<>();Set<Integer>wantedStrings=new HashSet<>();for(Integer v:classString.values())if(v!=null&&v>=0)wantedStrings.add(v);
        for(int i=0;i<typeSize;i++){int si=i32(d,typeOff+4*i);if(wantedStrings.contains(si))stringToType.put(si,i);} Map<Integer,Target>wanted=new HashMap<>();int methodSize=i32(d,88),methodOff=i32(d,92);
        for(Target t:ORIENTATION_TARGETS.keySet()){Integer csi=classString.get(t),nsi=methodName.get(t);if(csi==null||nsi==null||!stringToType.containsKey(csi))continue;int cti=stringToType.get(csi);for(int i=0;i<methodSize;i++){int ci=u16(d,methodOff+8*i),ni=i32(d,methodOff+8*i+4);if(ci==cti&&ni==nsi)wanted.put(i,t);}}
        if(wanted.isEmpty())return result;Set<Integer>wantedClassTypes=new HashSet<>();for(Integer csi:wantedStrings)if(stringToType.containsKey(csi))wantedClassTypes.add(stringToType.get(csi));int classSize=i32(d,96),classOff=i32(d,100);
        for(int ci=0;ci<classSize;ci++){int p=classOff+32*ci,classIdx=i32(d,p),classDataOff=i32(d,p+24);if(!wantedClassTypes.contains(classIdx)||classDataOff==0)continue;int o=classDataOff;int[]a=readUleb(d,o);int staticFields=a[0];o+=a[1];a=readUleb(d,o);int instanceFields=a[0];o+=a[1];a=readUleb(d,o);int direct=a[0];o+=a[1];a=readUleb(d,o);int virtual=a[0];o+=a[1];for(int k=0;k<staticFields+instanceFields;k++){a=readUleb(d,o);o+=a[1];a=readUleb(d,o);o+=a[1];}for(int mc:new int[]{direct,virtual}){int methodIdx=0;for(int k=0;k<mc;k++){a=readUleb(d,o);methodIdx+=a[0];o+=a[1];a=readUleb(d,o);o+=a[1];a=readUleb(d,o);int codeOff=a[0];o+=a[1];Target t=wanted.get(methodIdx);if(t!=null)result.put(t,codeOff);}}}
        return result;
    }

    private static Integer findSetRequestedOrientationMethod(byte[] d){Integer act=exactStringIndex(d,"Landroid/app/Activity;"),name=exactStringIndex(d,"setRequestedOrientation");if(act==null||name==null)return null;int typeSize=i32(d,64),typeOff=i32(d,68),activityType=-1;for(int i=0;i<typeSize;i++)if(i32(d,typeOff+4*i)==act){activityType=i;break;}if(activityType<0)return null;int methodSize=i32(d,88),methodOff=i32(d,92);for(int i=0;i<methodSize;i++)if(u16(d,methodOff+8*i)==activityType&&i32(d,methodOff+8*i+4)==name)return i;return null;}
    private static Integer exactStringIndex(byte[] d,String target){int[]offs=stringOffsets(d);byte[]raw=target.getBytes(StandardCharsets.UTF_8);for(int i=0;i<offs.length;i++){int[]a=readUleb(d,offs[i]);int s=offs[i]+a[1];if(s+raw.length<d.length&&matches(d,s,raw)&&d[s+raw.length]==0)return i;}return null;}
    private static int[] stringOffsets(byte[] d){if(d.length<112||d[0]!='d'||d[1]!='e'||d[2]!='x')throw new IllegalArgumentException("Not a DEX");int n=i32(d,56),off=i32(d,60);if(n<0||off<0||off+4L*n>d.length)throw new IllegalArgumentException("Invalid DEX string table");int[]a=new int[n];for(int i=0;i<n;i++)a[i]=i32(d,off+4*i);return a;}
    private static String decodeString(byte[] d,int off){int[]a=readUleb(d,off);int s=off+a[1],e=s;while(e<d.length&&d[e]!=0)e++;return new String(d,s,e-s,StandardCharsets.UTF_8).replace("\u00c0\u0080","\u0000");}
    private static int[] readUleb(byte[] d,int off){int value=0,shift=0;for(int i=0;i<5;i++){if(off+i>=d.length)throw new IllegalArgumentException("Truncated ULEB128");int b=d[off+i]&0xff;value|=(b&0x7f)<<shift;if((b&0x80)==0)return new int[]{value,i+1};shift+=7;}throw new IllegalArgumentException("Invalid ULEB128");}
    private static boolean matches(byte[]d,int off,byte[]n){if(off<0||off+n.length>d.length)return false;for(int i=0;i<n.length;i++)if(d[off+i]!=n[i])return false;return true;}
    private static int u16(byte[]b,int o){return(b[o]&0xff)|((b[o+1]&0xff)<<8);}private static int i32(byte[]b,int o){return(b[o]&0xff)|((b[o+1]&0xff)<<8)|((b[o+2]&0xff)<<16)|(b[o+3]<<24);}
}
