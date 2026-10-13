package com.bydspotifymanager.app;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Spotify 8.9.76.538 BYD geometry/font/resource settings. */
final class Spotify89ResourceUtil {
    static final int APP_NAME_RID = 0x7F130116;
    static final int ADAPTIVE_FOREGROUND_RID = 0x7F080782;
    static final String OLD_ADAPTIVE_FOREGROUND_PATH = "res/drawable/ic_launcher_renaissance_foreground.xml";
    static final String NEW_ADAPTIVE_FOREGROUND_PATH = "res/drawable/ic_launcher_renaissance_foreground.png";
    static final String RESOURCE_AUTHORITY_OLD = "com.spotify.mobile.android.mediaapi";

    enum FontPreset { STOCK, MODERATE, LARGE }

    static final class BrandingResult {
        final byte[] resources;
        final boolean modifiedIcon;
        BrandingResult(byte[] resources, boolean modifiedIcon) { this.resources=resources; this.modifiedIcon=modifiedIcon; }
    }
    static final class UiPatchResult {
        final byte[] resources;
        final int visualChanges;
        final int moderateChanges;
        final int largeChanges;
        UiPatchResult(byte[] resources, int visualChanges, int moderateChanges, int largeChanges) {
            this.resources=resources; this.visualChanges=visualChanges;
            this.moderateChanges=moderateChanges; this.largeChanges=largeChanges;
        }
    }
    private static final class ValuePos {
        int pos, dtype, dval, mapName; String key, type;
    }

    private Spotify89ResourceUtil() {}

    static byte[] patchIdentityAuthority(byte[] resources, String targetPackage) {
        String to=targetPackage + ".android.mediaapi_";
        if (to.length()!=RESOURCE_AUTHORITY_OLD.length()) throw new IllegalArgumentException("8.9 authority replacement length mismatch");
        byte[] old=RESOURCE_AUTHORITY_OLD.getBytes(StandardCharsets.US_ASCII);
        byte[] neu=to.getBytes(StandardCharsets.US_ASCII);
        int hits=count(resources,old);
        if (hits<=0) throw new IllegalArgumentException("Spotify 8.9 media API authority not found");
        return IoUtil.replaceSameLength(resources,old,neu);
    }

    static byte[] patchUi(byte[] resources, FontPreset preset) {
        UiPatchResult r=patchUiPart(resources,preset);
        if (r.visualChanges!=22) throw new IllegalArgumentException("Expected 22 Spotify 8.9 wide-screen visual values, found " + r.visualChanges);
        if (preset==FontPreset.MODERATE || preset==FontPreset.LARGE) {
            if (r.moderateChanges!=18) throw new IllegalArgumentException("Expected 18 Spotify 8.9 moderate font values, found " + r.moderateChanges);
            if (preset==FontPreset.LARGE && r.largeChanges!=18) throw new IllegalArgumentException("Expected 18 Spotify 8.9 large font values, found " + r.largeChanges);
        }
        return r.resources;
    }

    /** Applies whichever 8.9 UI values are present in one APKM resource split. */
    static UiPatchResult patchUiPart(byte[] resources, FontPreset preset) {
        byte[] out=resources.clone();
        int visual=applySimple(out, visualMap());
        int moderate=0, large=0;
        if (preset==FontPreset.MODERATE || preset==FontPreset.LARGE) {
            moderate=applySimple(out, moderateSimple()) + applyStyles(out, moderateStyles());
            if (preset==FontPreset.LARGE) {
                large=applySimple(out, largeSimple()) + applyStyles(out, largeStyles());
            }
        }
        return new UiPatchResult(out,visual,moderate,large);
    }

    /** Validation profile for the APKMirror APKM base.apk (some font dimensions live in density splits). */
    static void validateApkmBaseProfile(byte[] resources) {
        UiPatchResult r=patchUiPart(resources,FontPreset.MODERATE);
        if (r.resources.length!=resources.length) throw new IllegalArgumentException("Unexpected 8.9 APKM resource profile size change");
        if (r.visualChanges!=22) throw new IllegalArgumentException("Expected 22 Spotify 8.9 APKM visual values in base.apk, found " + r.visualChanges);
        if (r.moderateChanges!=10) throw new IllegalArgumentException("Expected 10 Spotify 8.9 APKM base font values, found " + r.moderateChanges);
        for (int[] spec:new int[][]{{APP_NAME_RID,0},{ADAPTIVE_FOREGROUND_RID,0}}) {
            List<ValuePos> vals=findValues(resources,spec[0],false);
            if (vals.isEmpty()) throw new IllegalArgumentException("Required 8.9 resource missing: 0x"+Integer.toHexString(spec[0]));
        }
    }

    static void validateProfile(byte[] resources) {
        byte[] copy=patchUi(resources,FontPreset.MODERATE);
        if (copy.length!=resources.length) throw new IllegalArgumentException("Unexpected 8.9 resource profile size change");
        for (int[] spec:new int[][]{{APP_NAME_RID,0},{ADAPTIVE_FOREGROUND_RID,0}}) {
            List<ValuePos> vals=findValues(resources,spec[0],false);
            if (vals.isEmpty()) throw new IllegalArgumentException("Required 8.9 resource missing: 0x"+Integer.toHexString(spec[0]));
        }
    }

    static BrandingResult patchBranding(byte[] resources, String appLabel, boolean modifyIcon) {
        if (appLabel==null) appLabel=""; appLabel=appLabel.trim();
        if (appLabel.isEmpty()) throw new IllegalArgumentException("App display name cannot be empty");
        if (appLabel.codePointCount(0,appLabel.length())>24) throw new IllegalArgumentException("8.9 app display name can be up to 24 characters");
        List<String> extra=new ArrayList<>(); extra.add(appLabel); if (modifyIcon) extra.add(NEW_ADAPTIVE_FOREGROUND_PATH);
        int rootHs=Spotify89BinaryUtil.u16(resources,2);
        Spotify89BinaryUtil.Pool pool=Spotify89BinaryUtil.parsePool(resources,rootHs);
        List<String> strings=new ArrayList<>(pool.strings);
        Map<String,Integer> idx=new LinkedHashMap<>();
        for (String s:extra) {
            int i=strings.indexOf(s); if (i<0) { i=strings.size(); strings.add(s); } idx.put(s,i);
        }
        byte[] arsc=resources.clone();
        patchStringResourceIndex(arsc,APP_NAME_RID,idx.get(appLabel),"app_name");
        if (modifyIcon) patchStringResourceIndex(arsc,ADAPTIVE_FOREGROUND_RID,idx.get(NEW_ADAPTIVE_FOREGROUND_PATH),"ic_launcher_renaissance_foreground");
        byte[] newPool=Spotify89BinaryUtil.buildPool(strings,pool);
        byte[] out=new byte[arsc.length-pool.size+newPool.length];
        System.arraycopy(arsc,0,out,0,rootHs);
        System.arraycopy(newPool,0,out,rootHs,newPool.length);
        System.arraycopy(arsc,rootHs+pool.size,out,rootHs+newPool.length,arsc.length-(rootHs+pool.size));
        Spotify89BinaryUtil.put32(out,4,out.length);
        return new BrandingResult(out,modifyIcon);
    }

    private static void patchStringResourceIndex(byte[] arsc, int rid, int newIdx, String expectedKey) {
        List<ValuePos> vals=findValues(arsc,rid,false);
        if (vals.isEmpty()) throw new IllegalArgumentException("Resource ID not found: 0x"+Integer.toHexString(rid));
        for (ValuePos v:vals) {
            if (!expectedKey.equals(v.key) || v.dtype!=3) throw new IllegalArgumentException("Unexpected resource shape for " + expectedKey);
            Spotify89BinaryUtil.put32(arsc,v.pos,newIdx);
        }
    }

    private static int applySimple(byte[] data, Map<Integer,Map<Integer,Integer>> maps) {
        int changed=0;
        for (Map.Entry<Integer,Map<Integer,Integer>> e:maps.entrySet()) {
            for (ValuePos v:findValues(data,e.getKey(),false)) {
                Integer nv=e.getValue().get(v.dval);
                if (v.dtype==5 && nv!=null) { Spotify89BinaryUtil.put32(data,v.pos,nv); changed++; }
            }
        }
        return changed;
    }
    private static int applyStyles(byte[] data, Map<Integer,Map<Integer,Integer>> maps) {
        int changed=0;
        for (Map.Entry<Integer,Map<Integer,Integer>> e:maps.entrySet()) {
            for (ValuePos v:findValues(data,e.getKey(),true)) {
                Integer nv=e.getValue().get(v.dval);
                if (v.mapName==0x01010095 && v.dtype==5 && nv!=null) { Spotify89BinaryUtil.put32(data,v.pos,nv); changed++; }
            }
        }
        return changed;
    }

    private static List<ValuePos> findValues(byte[] data, int rid, boolean complexItems) {
        List<ValuePos> out=new ArrayList<>();
        int rootHs=Spotify89BinaryUtil.u16(data,2);
        Spotify89BinaryUtil.Pool gp=Spotify89BinaryUtil.parsePool(data,rootHs);
        int o=rootHs+gp.size;
        int pkgId=(rid>>>24)&0xff, typeId=(rid>>>16)&0xff, entryId=rid&0xffff;
        while (o+8<=data.length) {
            int ctyp=Spotify89BinaryUtil.u16(data,o), chs=Spotify89BinaryUtil.u16(data,o+2), csz=Spotify89BinaryUtil.i32(data,o+4);
            if (csz<8 || o+csz>data.length) break;
            if (ctyp==0x0200 && Spotify89BinaryUtil.i32(data,o+8)==pkgId) {
                int typeStrings=Spotify89BinaryUtil.i32(data,o+268), keyStrings=Spotify89BinaryUtil.i32(data,o+276);
                List<String> tnames=Spotify89BinaryUtil.parsePool(data,o+typeStrings).strings;
                List<String> knames=Spotify89BinaryUtil.parsePool(data,o+keyStrings).strings;
                int p=o+chs, end=o+csz;
                while (p+8<=end) {
                    int t=Spotify89BinaryUtil.u16(data,p), h=Spotify89BinaryUtil.u16(data,p+2), s=Spotify89BinaryUtil.i32(data,p+4);
                    if (s<8 || p+s>end) break;
                    if (t==0x0201 && (data[p+8]&0xff)==typeId) {
                        int entryCount=Spotify89BinaryUtil.i32(data,p+12), entriesStart=Spotify89BinaryUtil.i32(data,p+16);
                        if (entryId<entryCount) {
                            int eoff=Spotify89BinaryUtil.i32(data,p+h+4*entryId);
                            if (eoff!=-1) {
                                int ep=p+entriesStart+eoff, esize=Spotify89BinaryUtil.u16(data,ep), flags=Spotify89BinaryUtil.u16(data,ep+2), keyIdx=Spotify89BinaryUtil.i32(data,ep+4);
                                String key=keyIdx>=0&&keyIdx<knames.size()?knames.get(keyIdx):"";
                                String type=typeId>0&&typeId-1<tnames.size()?tnames.get(typeId-1):"";
                                if ((flags&1)!=0) {
                                    if (complexItems) {
                                        int cnt=Spotify89BinaryUtil.i32(data,ep+12), q=ep+16;
                                        for (int j=0;j<cnt && q+12<=p+s;j++,q+=12) {
                                            int name=Spotify89BinaryUtil.i32(data,q), vp=q+4;
                                            ValuePos v=new ValuePos(); v.pos=vp+4; v.dtype=data[vp+3]&0xff; v.dval=Spotify89BinaryUtil.i32(data,vp+4); v.mapName=name; v.key=key; v.type=type; out.add(v);
                                        }
                                    }
                                } else if (!complexItems) {
                                    int vp=ep+esize;
                                    ValuePos v=new ValuePos(); v.pos=vp+4; v.dtype=data[vp+3]&0xff; v.dval=Spotify89BinaryUtil.i32(data,vp+4); v.mapName=0; v.key=key; v.type=type; out.add(v);
                                }
                            }
                        }
                    }
                    p+=s;
                }
                return out;
            }
            o+=csz;
        }
        return out;
    }

    private static Map<Integer,Map<Integer,Integer>> visualMap() {
        Map<Integer,Map<Integer,Integer>> m=new LinkedHashMap<>();
        add(m,0x7f0704c6,0x00000c01,0x00000f01); add(m,0x7f0704c9,0x00003801,0x00004601);
        add(m,0x7f070526,0x00004001,0x00005001); add(m,0x7f070529,0x00003001,0x00003c01); add(m,0x7f07053b,0x00001801,0x00001e01);
        add(m,0x7f070811,0x00003801,0x00004601); add(m,0x7f070811,0x00002701,0x00003a01); add(m,0x7f070908,0x00004801,0x00005301);
        add(m,0x7f070a61,0x00003001,0x00003c01); add(m,0x7f070a64,0x00003001,0x00003c01);
        // Wide-screen content gutter: reduce the stock 134/144/180dp margins to 24dp.
        // This lets the Home content use more of the display while retaining a clean edge gap.
        add(m,0x7f07006d,0x00008601,0x00001801); add(m,0x7f07006d,0x00009001,0x00001801); add(m,0x7f07006d,0x0000b401,0x00001801);
        for (int rid:new int[]{0x7f0703bd,0x7f0703be,0x7f070484,0x7f070486}) { add(m,rid,0x00004001,0x00003001); add(m,rid,0x0000a001,0x00003501); }
        return m;
    }
    private static Map<Integer,Map<Integer,Integer>> moderateSimple() { Map<Integer,Map<Integer,Integer>>m=new LinkedHashMap<>();
        add(m,0x7f070a59,0x00000e02,0x00001402); add(m,0x7f070a59,0x00001002,0x00001602); add(m,0x7f070a59,0x00001202,0x00001902);
        add(m,0x7f070a5a,0x00001202,0x00001b02); add(m,0x7f070a5a,0x00001402,0x00001e02); add(m,0x7f070a5a,0x00001602,0x00002202); return m; }
    private static Map<Integer,Map<Integer,Integer>> moderateStyles(){ Map<Integer,Map<Integer,Integer>>m=new LinkedHashMap<>();
        add(m,0x7f140367,0x00000902,0x00000d02); add(m,0x7f140369,0x00001002,0x00001702); add(m,0x7f14036a,0x00001002,0x00001702);
        add(m,0x7f14036b,0x00000d02,0x00001202); add(m,0x7f14036c,0x00000d02,0x00001202); add(m,0x7f14036f,0x00000b02,0x00001002);
        add(m,0x7f140372,0x00000b02,0x00001002); add(m,0x7f140375,0x00000a02,0x00000e02); add(m,0x7f140378,0x00001802,0x00002202); add(m,0x7f14037a,0x00001402,0x00001d02); return m; }
    private static Map<Integer,Map<Integer,Integer>> largeSimple(){ Map<Integer,Map<Integer,Integer>>m=new LinkedHashMap<>();
        add(m,0x7f070a59,0x00001402,0x00001702); add(m,0x7f070a59,0x00001602,0x00001902); add(m,0x7f070a59,0x00001902,0x00001d02);
        add(m,0x7f070a5a,0x00001b02,0x00001f02); add(m,0x7f070a5a,0x00001e02,0x00002302); add(m,0x7f070a5a,0x00002202,0x00002702); return m; }
    private static Map<Integer,Map<Integer,Integer>> largeStyles(){ Map<Integer,Map<Integer,Integer>>m=new LinkedHashMap<>();
        add(m,0x7f140367,0x00000d02,0x00000f02); add(m,0x7f140369,0x00001702,0x00001a02); add(m,0x7f14036a,0x00001702,0x00001a02);
        add(m,0x7f14036b,0x00001202,0x00001502); add(m,0x7f14036c,0x00001202,0x00001502); add(m,0x7f14036f,0x00001002,0x00001202);
        add(m,0x7f140372,0x00001002,0x00001202); add(m,0x7f140375,0x00000e02,0x00001002); add(m,0x7f140378,0x00002202,0x00002702); add(m,0x7f14037a,0x00001d02,0x00002102); return m; }
    private static void add(Map<Integer,Map<Integer,Integer>> m,int rid,int from,int to){ m.computeIfAbsent(rid,k->new LinkedHashMap<>()).put(from,to); }
    private static int count(byte[] hay, byte[] needle) { int n=0; for(int i=0;i+needle.length<=hay.length;){ boolean ok=true; for(int j=0;j<needle.length;j++) if(hay[i+j]!=needle[j]){ok=false;break;} if(ok){n++;i+=needle.length;}else i++; } return n; }
}
