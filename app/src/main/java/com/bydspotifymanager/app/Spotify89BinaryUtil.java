package com.bydspotifymanager.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Binary Android resource/XML helpers for Spotify 8.9. */
final class Spotify89BinaryUtil {
    static final int NO_INDEX = 0xffffffff;

    static final class Pool {
        final List<String> strings;
        final int size;
        final int flags;
        final int styleCount;
        final int[] styleOffsets;
        final byte[] styleData;
        final int headerSize;
        Pool(List<String> strings, int size, int flags, int styleCount, int[] styleOffsets, byte[] styleData, int headerSize) {
            this.strings = strings; this.size = size; this.flags = flags; this.styleCount = styleCount;
            this.styleOffsets = styleOffsets; this.styleData = styleData; this.headerSize = headerSize;
        }
    }

    private Spotify89BinaryUtil() {}

    static int u16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8); }
    static int i32(byte[] b, int o) { return (b[o]&0xff) | ((b[o+1]&0xff)<<8) | ((b[o+2]&0xff)<<16) | (b[o+3]<<24); }
    static long u32(byte[] b, int o) { return i32(b,o) & 0xffffffffL; }
    static void put16(byte[] b, int o, int v) { b[o]=(byte)v; b[o+1]=(byte)(v>>>8); }
    static void put32(byte[] b, int o, int v) { b[o]=(byte)v; b[o+1]=(byte)(v>>>8); b[o+2]=(byte)(v>>>16); b[o+3]=(byte)(v>>>24); }

    private static int[] readLen8(byte[] b, int o) {
        int x=b[o++]&0xff;
        if ((x&0x80)!=0) { x=((x&0x7f)<<8)|(b[o++]&0xff); }
        return new int[]{x,o};
    }
    private static int[] readLen16(byte[] b, int o) {
        int x=u16(b,o); o+=2;
        if ((x&0x8000)!=0) { x=((x&0x7fff)<<16)|u16(b,o); o+=2; }
        return new int[]{x,o};
    }
    private static void writeLen8(ByteArrayOutputStream out, int v) {
        if (v < 0x80) out.write(v);
        else if (v < 0x8000) { out.write(((v>>8)&0x7f)|0x80); out.write(v&0xff); }
        else throw new IllegalArgumentException("String length too large: " + v);
    }
    private static void writeLen16(ByteArrayOutputStream out, int v) {
        if (v < 0x8000) { out.write(v&0xff); out.write((v>>>8)&0xff); }
        else if (v >= 0) {
            int a=(v>>>16)|0x8000, c=v&0xffff;
            out.write(a&0xff); out.write((a>>>8)&0xff); out.write(c&0xff); out.write((c>>>8)&0xff);
        } else throw new IllegalArgumentException("String length too large: " + v);
    }

    static Pool parsePool(byte[] data, int off) {
        if (off < 0 || off + 28 > data.length || u16(data,off) != 1) throw new IllegalArgumentException("Invalid string pool");
        int hs=u16(data,off+2), sz=i32(data,off+4), sc=i32(data,off+8), sty=i32(data,off+12);
        int flags=i32(data,off+16), stringsStart=i32(data,off+20), stylesStart=i32(data,off+24);
        if (sz < hs || off+sz>data.length || sc<0 || sty<0) throw new IllegalArgumentException("Invalid string pool bounds");
        int[] strOffs=new int[sc];
        for (int i=0;i<sc;i++) strOffs[i]=i32(data,off+hs+4*i);
        int[] styleOffs=new int[sty];
        int styleBase=off+hs+4*sc;
        for (int i=0;i<sty;i++) styleOffs[i]=i32(data,styleBase+4*i);
        boolean utf8=(flags&0x100)!=0;
        List<String> arr=new ArrayList<>(sc);
        for (int oo:strOffs) {
            int p=off+stringsStart+oo;
            if (utf8) {
                int[] a=readLen8(data,p); p=a[1]; int[] bl=readLen8(data,p); p=bl[1];
                arr.add(decodeUtf8PoolString(data,p,bl[0]));
            } else {
                int[] l=readLen16(data,p); p=l[1];
                arr.add(new String(data,p,l[0]*2,StandardCharsets.UTF_16LE));
            }
        }
        byte[] styleData=stylesStart==0?new byte[0]:Arrays.copyOfRange(data,off+stylesStart,off+sz);
        return new Pool(arr,sz,flags,sty,styleOffs,styleData,hs);
    }

    static byte[] buildPool(List<String> strings, Pool meta) {
        boolean utf8=(meta.flags&0x100)!=0;
        ByteArrayOutputStream blob=new ByteArrayOutputStream();
        int[] offsets=new int[strings.size()];
        for (int i=0;i<strings.size();i++) {
            String s=strings.get(i); offsets[i]=blob.size();
            if (utf8) {
                byte[] bs=encodeUtf8PoolString(s);
                int u16len=s.length();
                writeLen8(blob,u16len); writeLen8(blob,bs.length); blob.write(bs,0,bs.length); blob.write(0);
            } else {
                byte[] bs=s.getBytes(StandardCharsets.UTF_16LE);
                writeLen16(blob,bs.length/2); blob.write(bs,0,bs.length); blob.write(0); blob.write(0);
            }
        }
        while ((blob.size()&3)!=0) blob.write(0);
        int hs=28;
        int stringsStart=hs+4*strings.size()+4*meta.styleCount;
        int stylesStart=meta.styleCount>0?stringsStart+blob.size():0;
        int size=(meta.styleCount>0?stylesStart+meta.styleData.length:stringsStart+blob.size());
        size=(size+3)&~3;
        byte[] out=new byte[size];
        put16(out,0,1); put16(out,2,hs); put32(out,4,size);
        put32(out,8,strings.size()); put32(out,12,meta.styleCount); put32(out,16,meta.flags);
        put32(out,20,stringsStart); put32(out,24,stylesStart);
        for (int i=0;i<offsets.length;i++) put32(out,hs+4*i,offsets[i]);
        int base=hs+4*strings.size();
        for (int i=0;i<meta.styleOffsets.length;i++) put32(out,base+4*i,meta.styleOffsets[i]);
        byte[] bb=blob.toByteArray(); System.arraycopy(bb,0,out,stringsStart,bb.length);
        if (meta.styleCount>0) System.arraycopy(meta.styleData,0,out,stylesStart,meta.styleData.length);
        return out;
    }


    // Android UTF-8 resource pools are encoded per UTF-16 code unit (CESU-8 style).
    // Keeping surrogate code units intact is important when rebuilding a pool that contains emoji.
    private static String decodeUtf8PoolString(byte[] data, int off, int len) {
        int end=off+len; StringBuilder sb=new StringBuilder();
        while(off<end){
            int b0=data[off++]&0xff;
            if((b0&0x80)==0){ sb.append((char)b0); continue; }
            if((b0&0xe0)==0xc0){
                if(off>=end)throw new IllegalArgumentException("Truncated UTF-8 resource string");
                int b1=data[off++]&0x3f; sb.append((char)(((b0&0x1f)<<6)|b1)); continue;
            }
            if((b0&0xf0)==0xe0){
                if(off+1>=end)throw new IllegalArgumentException("Truncated UTF-8 resource string");
                int b1=data[off++]&0x3f,b2=data[off++]&0x3f; sb.append((char)(((b0&0x0f)<<12)|(b1<<6)|b2)); continue;
            }
            if((b0&0xf8)==0xf0){
                if(off+2>=end)throw new IllegalArgumentException("Truncated UTF-8 resource string");
                int b1=data[off++]&0x3f,b2=data[off++]&0x3f,b3=data[off++]&0x3f;
                int cp=((b0&7)<<18)|(b1<<12)|(b2<<6)|b3; sb.append(Character.toChars(cp)); continue;
            }
            throw new IllegalArgumentException("Invalid UTF-8 resource string");
        }
        return sb.toString();
    }

    private static byte[] encodeUtf8PoolString(String s) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<s.length();i++){
            int c=s.charAt(i);
            if(c==0){ out.write(0xc0); out.write(0x80); }
            else if(c<=0x7f) out.write(c);
            else if(c<=0x7ff){ out.write(0xc0|(c>>>6)); out.write(0x80|(c&0x3f)); }
            else { out.write(0xe0|(c>>>12)); out.write(0x80|((c>>>6)&0x3f)); out.write(0x80|(c&0x3f)); }
        }
        return out.toByteArray();
    }

    static byte[] replaceXmlStrings(byte[] data, Map<String,String> replacements) {
        int rootHs=u16(data,2); Pool meta=parsePool(data,rootHs); List<String> old=meta.strings; List<String> neu=new ArrayList<>(old);
        Set<String> missing=new LinkedHashSet<>(replacements.keySet());
        for (int i=0;i<old.size();i++) {
            String s=old.get(i); String n=replacements.get(s);
            if (n!=null) { neu.set(i,n); missing.remove(s); }
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing XML strings: " + missing);
        byte[] pool=buildPool(neu,meta);
        byte[] out=new byte[data.length-meta.size+pool.length];
        System.arraycopy(data,0,out,0,rootHs); System.arraycopy(pool,0,out,rootHs,pool.length);
        System.arraycopy(data,rootHs+meta.size,out,rootHs+pool.length,data.length-(rootHs+meta.size));
        put32(out,4,out.length);
        return out;
    }

    static int mapIndex(int idx, Map<Integer,Integer> mapping) { return idx==NO_INDEX?idx:mapping.getOrDefault(idx,idx); }
    static int attrResId(int nameIdx, List<Integer> resMap) { return nameIdx>=0&&nameIdx<resMap.size()?resMap.get(nameIdx):NO_INDEX; }

    static byte[] concat(byte[]... chunks) {
        int n=0; for (byte[] c:chunks) n+=c.length;
        byte[] out=new byte[n]; int p=0;
        for (byte[] c:chunks) { System.arraycopy(c,0,out,p,c.length); p+=c.length; }
        return out;
    }
}
