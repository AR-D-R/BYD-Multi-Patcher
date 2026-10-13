package com.bydspotifymanager.app;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Spotify 8.9 binary-manifest edits. */
final class Spotify89ManifestUtil {
    static final String OLD_PACKAGE="com.spotify.music";
    private static final Set<Integer> PORTRAIT_VALUES=new HashSet<>(Arrays.asList(1,7,9,12));
    private static final String[] KNOWN_EXACT={
            OLD_PACKAGE,
            OLD_PACKAGE+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            OLD_PACKAGE+".permission.C2D_MESSAGE",
            OLD_PACKAGE+".permission.INTERNAL_BROADCAST",
            OLD_PACKAGE+".permission.SECURED_BROADCAST",
            OLD_PACKAGE+".androidx-startup",
            OLD_PACKAGE+".share",
            OLD_PACKAGE+".pushnotificationsv2",
            OLD_PACKAGE+".profile",
            OLD_PACKAGE+".vtec",
            OLD_PACKAGE+".calimage",
            OLD_PACKAGE+".early-initialization",
            OLD_PACKAGE+".imagepicker",
            OLD_PACKAGE+".sso.afterlogindummytask"
    };

    static final class VersionInfo { final String name; final long code; VersionInfo(String n,long c){name=n;code=c;} }
    static final class PortraitResult { final byte[] data; final int count; PortraitResult(byte[]d,int c){data=d;count=c;} }

    private Spotify89ManifestUtil() {}

    static List<String> scanStrings(byte[] data) {
        if (data.length<36) throw new IllegalArgumentException("AndroidManifest.xml is unexpectedly small");
        int pos=8;
        if (Spotify89BinaryUtil.u16(data,pos)!=1) throw new IllegalArgumentException("Expected binary XML string pool at offset 8");
        Spotify89BinaryUtil.Pool p=Spotify89BinaryUtil.parsePool(data,pos);
        return p.strings;
    }

    static boolean isOriginalPackage(byte[] data) { return scanStrings(data).contains(OLD_PACKAGE); }

    static VersionInfo versionInfo(byte[] data) {
        int rootHs=Spotify89BinaryUtil.u16(data,2);
        Spotify89BinaryUtil.Pool meta=Spotify89BinaryUtil.parsePool(data,rootHs);
        List<String> strings=meta.strings;
        int o=rootHs+meta.size;
        if (o+8<=data.length && Spotify89BinaryUtil.u16(data,o)==0x0180) o+=Spotify89BinaryUtil.i32(data,o+4);
        while (o+8<=data.length) {
            int typ=Spotify89BinaryUtil.u16(data,o), sz=Spotify89BinaryUtil.i32(data,o+4);
            if (sz<8||o+sz>data.length) break;
            if (typ==0x0102 && "manifest".equals(strings.get(Spotify89BinaryUtil.i32(data,o+20)))) {
                int attrStart=Spotify89BinaryUtil.u16(data,o+24), attrSize=Spotify89BinaryUtil.u16(data,o+26), attrCount=Spotify89BinaryUtil.u16(data,o+28);
                int ap=o+16+attrStart; String versionName=null; long versionCode=-1;
                for(int i=0;i<attrCount;i++){
                    int ao=ap+i*attrSize, nameIdx=Spotify89BinaryUtil.i32(data,ao+4), rawIdx=Spotify89BinaryUtil.i32(data,ao+8), dtype=data[ao+15]&0xff, dval=Spotify89BinaryUtil.i32(data,ao+16);
                    String name=strings.get(nameIdx);
                    if("versionName".equals(name)){
                        if(rawIdx!=Spotify89BinaryUtil.NO_INDEX) versionName=strings.get(rawIdx);
                        else if(dtype==3&&dval>=0&&dval<strings.size()) versionName=strings.get(dval);
                    } else if("versionCode".equals(name)) versionCode=dval&0xffffffffL;
                }
                return new VersionInfo(versionName,versionCode);
            }
            o+=sz;
        }
        return new VersionInfo(null,-1);
    }

    static byte[] patchIdentity(byte[] data, String newPackage) {
        if(newPackage.length()!=OLD_PACKAGE.length()) throw new IllegalArgumentException("8.9 clone package must preserve length");
        Map<String,String> repl=new LinkedHashMap<>();
        for(String s:KNOWN_EXACT) repl.put(s,s.replace(OLD_PACKAGE,newPackage));
        String leaf=newPackage.substring(newPackage.lastIndexOf('.')+1);
        repl.put("androidx.car.app.connection",leaf+"xxx.car.app.connection");
        byte[] b=data.clone();
        List<String> strings=scanStrings(data);
        int pos=8, headerSize=Spotify89BinaryUtil.u16(b,pos+2), stringCount=Spotify89BinaryUtil.i32(b,pos+8), stringsStart=Spotify89BinaryUtil.i32(b,pos+20);
        boolean changedPackage=false;
        for(int i=0;i<stringCount;i++){
            int rel=Spotify89BinaryUtil.i32(b,pos+headerSize+4*i), start=pos+stringsStart+rel;
            int first=Spotify89BinaryUtil.u16(b,start), chars, lenBytes;
            if((first&0x8000)!=0){ chars=((first&0x7fff)<<16)|Spotify89BinaryUtil.u16(b,start+2); lenBytes=4; } else { chars=first; lenBytes=2; }
            int ps=start+lenBytes, pe=ps+chars*2;
            String s=new String(b,ps,pe-ps,StandardCharsets.UTF_16LE), n=repl.get(s);
            if(n!=null){ byte[] enc=n.getBytes(StandardCharsets.UTF_16LE); if(enc.length!=pe-ps) throw new IllegalArgumentException("Manifest replacement length mismatch: "+s); System.arraycopy(enc,0,b,ps,enc.length); if(OLD_PACKAGE.equals(s)) changedPackage=true; }
        }
        if(!changedPackage) throw new IllegalArgumentException("Selected APK is not an original com.spotify.music package");
        return b;
    }

    static byte[] patchAutoResume(byte[] data, String newPackage) {
        String resumeService=newPackage+".AutoResumeService";
        Map<String,String> replacements=new LinkedHashMap<>();
        replacements.put("android.media.MediaRoute2ProviderService","byd.intent.action.RESTORE_PLAYBACK");
        replacements.put("com.spotify.connect.mediarouteprovider.SpotifyMediaRouteProviderService",resumeService);
        data=Spotify89BinaryUtil.replaceXmlStrings(data,replacements);
        int rootHs=Spotify89BinaryUtil.u16(data,2); Spotify89BinaryUtil.Pool meta=Spotify89BinaryUtil.parsePool(data,rootHs); List<String> ss=meta.strings;
        int o=rootHs+meta.size;
        if(Spotify89BinaryUtil.u16(data,o)!=0x0180) throw new IllegalArgumentException("Manifest resource map missing");
        int h=Spotify89BinaryUtil.u16(data,o+2), s=Spotify89BinaryUtil.i32(data,o+4); List<Integer> resmap=new ArrayList<>();
        for(int p=o+h;p<o+s;p+=4)resmap.add(Spotify89BinaryUtil.i32(data,p));
        List<byte[]> chunks=new ArrayList<>(); chunks.add(Arrays.copyOfRange(data,o,o+s)); o+=s; boolean found=false;
        while(o<data.length){
            int typ=Spotify89BinaryUtil.u16(data,o), sz=Spotify89BinaryUtil.i32(data,o+4); if(sz<8||o+sz>data.length)throw new IllegalArgumentException("Bad manifest chunk");
            byte[] c=Arrays.copyOfRange(data,o,o+sz);
            if(typ==0x0102){
                String tag=ss.get(Spotify89BinaryUtil.i32(c,20)); int attrStart=Spotify89BinaryUtil.u16(c,24), attrSize=Spotify89BinaryUtil.u16(c,26), attrCount=Spotify89BinaryUtil.u16(c,28), ap=16+attrStart;
                List<Attr> attrs=new ArrayList<>();
                for(int i=0;i<attrCount;i++){ int ao=ap+i*attrSize, ni=Spotify89BinaryUtil.i32(c,ao+4), raw=Spotify89BinaryUtil.i32(c,ao+8), dt=c[ao+15]&0xff, dv=Spotify89BinaryUtil.i32(c,ao+16); String val=raw!=Spotify89BinaryUtil.NO_INDEX?ss.get(raw):(dt==3&&dv>=0&&dv<ss.size()?ss.get(dv):null); attrs.add(new Attr(ss.get(ni),ao,ni,dt,dv,raw,val)); }
                if("service".equals(tag) && attrs.stream().anyMatch(a->"name".equals(a.name)&&resumeService.equals(a.value))){
                    found=true;
                    for(Attr a:attrs) if("enabled".equals(a.name)){ if(a.dtype!=0x12||a.dval!=0)throw new IllegalArgumentException("Unexpected restore service enabled state"); Spotify89BinaryUtil.put32(c,a.off+16,0xffffffff); }
                    int fgIdx=ss.indexOf("foregroundServiceType"), androidNs=ss.indexOf("http://schemas.android.com/apk/res/android"); if(fgIdx<0||androidNs<0)throw new IllegalArgumentException("Manifest foregroundServiceType metadata missing");
                    byte[] attr=new byte[20]; Spotify89BinaryUtil.put32(attr,0,androidNs); Spotify89BinaryUtil.put32(attr,4,fgIdx); Spotify89BinaryUtil.put32(attr,8,0xffffffff); Spotify89BinaryUtil.put16(attr,12,8); attr[14]=0; attr[15]=0x11; Spotify89BinaryUtil.put32(attr,16,2);
                    int targetRid=fgIdx<resmap.size()?resmap.get(fgIdx):0xffffffff, ins=attrCount;
                    for(int i=0;i<attrs.size();i++){ int rid=attrs.get(i).nameIdx<resmap.size()?resmap.get(attrs.get(i).nameIdx):0xffffffff; if(Integer.compareUnsigned(rid,targetRid)>0){ins=i;break;} }
                    int insertPos=ap+ins*attrSize; byte[] nc=new byte[c.length+attrSize]; System.arraycopy(c,0,nc,0,insertPos); System.arraycopy(attr,0,nc,insertPos,attr.length); System.arraycopy(c,insertPos,nc,insertPos+attrSize,c.length-insertPos); c=nc; Spotify89BinaryUtil.put32(c,4,sz+attrSize); Spotify89BinaryUtil.put16(c,28,attrCount+1);
                }
            }
            chunks.add(c); o+=sz;
        }
        if(!found)throw new IllegalArgumentException("Known Spotify 8.9 restore service slot not found");
        int prefix=rootHs+meta.size, total=prefix; for(byte[] c:chunks)total+=c.length; byte[] out=new byte[total]; System.arraycopy(data,0,out,0,prefix); int p=prefix; for(byte[] c:chunks){System.arraycopy(c,0,out,p,c.length);p+=c.length;} Spotify89BinaryUtil.put32(out,4,out.length); return out;
    }

    static byte[] disableAutoResumeTrigger(byte[] manifest) {
        String from="byd.intent.action.RESTORE_PLAYBACK";
        String to="byd.intent.action.RESTORE_DISABLED";
        if(from.length()!=to.length())throw new IllegalStateException("AutoResume action replacement must preserve string length");
        return Spotify89BinaryUtil.replaceXmlStrings(manifest,Collections.singletonMap(from,to));
    }

    static PortraitResult patchPortraitOrientations(byte[] data) {
        int rootHs=Spotify89BinaryUtil.u16(data,2); Spotify89BinaryUtil.Pool meta=Spotify89BinaryUtil.parsePool(data,rootHs); List<String> strings=meta.strings; int prefix=rootHs+meta.size, o=prefix, count=0; List<byte[]> chunks=new ArrayList<>();
        while(o<data.length){ int typ=Spotify89BinaryUtil.u16(data,o), sz=Spotify89BinaryUtil.i32(data,o+4); if(sz<8||o+sz>data.length)throw new IllegalArgumentException("Bad manifest chunk"); byte[] c=Arrays.copyOfRange(data,o,o+sz);
            if(typ==0x0102){ String tag=strings.get(Spotify89BinaryUtil.i32(c,20)); if("activity".equals(tag)||"activity-alias".equals(tag)){ int as=Spotify89BinaryUtil.u16(c,24), az=Spotify89BinaryUtil.u16(c,26), ac=Spotify89BinaryUtil.u16(c,28), ap=16+as; for(int i=0;i<ac;i++){ int ao=ap+i*az, ni=Spotify89BinaryUtil.i32(c,ao+4), dt=c[ao+15]&0xff, dv=Spotify89BinaryUtil.i32(c,ao+16); if("screenOrientation".equals(strings.get(ni))&&PORTRAIT_VALUES.contains(dv)){ if(dt!=0x10)throw new IllegalArgumentException("Unexpected screenOrientation type"); Spotify89BinaryUtil.put32(c,ao+16,0xffffffff); count++; } } } }
            chunks.add(c); o+=sz; }
        int total=prefix; for(byte[] c:chunks)total+=c.length; byte[] out=new byte[total]; System.arraycopy(data,0,out,0,prefix); int p=prefix; for(byte[] c:chunks){System.arraycopy(c,0,out,p,c.length);p+=c.length;} Spotify89BinaryUtil.put32(out,4,out.length); return new PortraitResult(out,count);
    }

    private static final class Attr { String name,value; int off,nameIdx,dtype,dval,raw; Attr(String n,int o,int ni,int dt,int dv,int r,String v){name=n;off=o;nameIdx=ni;dtype=dt;dval=dv;raw=r;value=v;} }
}
