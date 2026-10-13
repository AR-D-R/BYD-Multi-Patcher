package com.bydspotifymanager.app;

import android.content.Context;
import android.os.Build;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Spotify 8.9.76.538 APKMirror APKM patch engine. */
final class ApkPatchEngine89 {
    interface Progress { void onProgress(String message); }
    static final String VERSION="8.9.76.538";
    static final long VERSION_CODE=119017142L;
    private static final int EXPECTED_APKM_APK_COUNT=30;
    private static final int EXPECTED_BASE_DEX_COUNT=9;
    private static final int EXPECTED_PACKAGE_MARKERS=5;
    private static final int EXPECTED_RUNTIME_PORTRAIT_MARKERS=3;
    private static final int EXPECTED_APKM_VISUAL_CHANGES=22;
    private static final int EXPECTED_APKM_FONT_CHANGES=16;
    private static final String AUTO_RESUME_ASSET="auto_resume/classes.dex";

    private static final Set<String> REQUIRED_SPLITS=new LinkedHashSet<>(Arrays.asList(
            "base.apk",
            "split_config.arm64_v8a.apk",
            "split_config.mdpi.apk",
            "split_config.xxhdpi.apk",
            "split_config.xxxhdpi.apk"
    ));

    static final class Validation {
        final boolean ok; final String detail;
        Validation(boolean ok,String detail){this.ok=ok;this.detail=detail;}
    }

    private static final class ResourceTotals {
        int visual, moderate, large;
        void add(Spotify89ResourceUtil.UiPatchResult r){
            if(r==null)return;
            visual+=r.visualChanges; moderate+=r.moderateChanges; large+=r.largeChanges;
        }
    }

    private ApkPatchEngine89(){}

    /** Validates the exact APKM-style source profile used by this Manager build. */
    static Validation validateSource(File apkm) {
        if(apkm==null||!apkm.isFile())return new Validation(false,"Spotify 8.9 APKM file not found.");
        File baseTmp=null;
        try(ZipFile bundle=new ZipFile(apkm)){
            List<String> apkNames=new ArrayList<>();
            for(Enumeration<? extends ZipEntry> en=bundle.entries();en.hasMoreElements();){
                String n=en.nextElement().getName(); if(n.endsWith(".apk")&&!n.contains("/"))apkNames.add(n);
            }
            if(apkNames.size()!=EXPECTED_APKM_APK_COUNT)return new Validation(false,
                    "Expected the supported Spotify 8.9 APKM variant with "+EXPECTED_APKM_APK_COUNT+" APK splits; found "+apkNames.size()+".");
            for(String required:REQUIRED_SPLITS)if(!apkNames.contains(required))return new Validation(false,
                    "Spotify 8.9 APKM variant mismatch: missing "+required+". Use the linked arm64-v8a + x86, 160-640dpi APKM variant.");

            ZipEntry baseEntry=bundle.getEntry("base.apk");
            if(baseEntry==null)return new Validation(false,"This is not a Spotify APKM bundle: base.apk is missing.");
            baseTmp=File.createTempFile("spotify89_base_",".apk",apkm.getParentFile());
            try(InputStream in=new BufferedInputStream(bundle.getInputStream(baseEntry));OutputStream out=new BufferedOutputStream(new FileOutputStream(baseTmp))){IoUtil.copy(in,out);}
            String baseHash=IoUtil.sha256(baseTmp);
            if(!BuildConfigData.V89_BASE_SHA256.equalsIgnoreCase(baseHash))return new Validation(false,
                    "Spotify 8.9 base.apk does not match the supported APKMirror bundle. SHA-256: "+baseHash);
            Validation baseValidation=validateBaseApk(baseTmp);
            if(!baseValidation.ok)return baseValidation;
            return new Validation(true,"Verified original Spotify "+VERSION+" APKM · "+apkNames.size()+" APK splits");
        }catch(Exception e){return new Validation(false,e.getClass().getSimpleName()+": "+e.getMessage());}
        finally{if(baseTmp!=null&&!baseTmp.delete())baseTmp.deleteOnExit();}
    }

    private static Validation validateBaseApk(File apk){
        try(ZipFile z=new ZipFile(apk)){
            String[] required={"AndroidManifest.xml","resources.arsc","classes.dex",Spotify89PanelUtil.WIDE_LAYOUT_PATH};
            for(String n:required)if(z.getEntry(n)==null)return new Validation(false,"Missing required Spotify 8.9 base.apk entry: "+n);
            byte[] manifest=IoUtil.readAll(z.getInputStream(z.getEntry("AndroidManifest.xml")));
            if(!Spotify89ManifestUtil.isOriginalPackage(manifest))return new Validation(false,"Selected APKM base is not original com.spotify.music.");
            Spotify89ManifestUtil.VersionInfo vi=Spotify89ManifestUtil.versionInfo(manifest);
            if(!VERSION.equals(vi.name)||vi.code!=VERSION_CODE)return new Validation(false,
                    "Expected Spotify "+VERSION+" ("+VERSION_CODE+") but found "+vi.name+" ("+vi.code+").");
            Spotify89ManifestUtil.PortraitResult mr=Spotify89ManifestUtil.patchPortraitOrientations(manifest);
            if(mr.count!=12)return new Validation(false,"Spotify 8.9 portrait-lock profile mismatch: expected 12, found "+mr.count+".");
            List<String> ms=Spotify89ManifestUtil.scanStrings(manifest);
            if(!ms.contains("com.spotify.connect.mediarouteprovider.SpotifyMediaRouteProviderService")||
                    !ms.contains("android.media.MediaRoute2ProviderService")||!ms.contains("foregroundServiceType"))
                return new Validation(false,"Spotify 8.9 auto-resume service slot was not found.");

            byte[] resources=IoUtil.readAll(z.getInputStream(z.getEntry("resources.arsc")));
            if(count(resources,Spotify89ResourceUtil.RESOURCE_AUTHORITY_OLD.getBytes(StandardCharsets.US_ASCII))==0)
                return new Validation(false,"Spotify 8.9 media API provider authority was not found.");
            Spotify89ResourceUtil.validateApkmBaseProfile(resources);

            byte[] wide=IoUtil.readAll(z.getInputStream(z.getEntry(Spotify89PanelUtil.WIDE_LAYOUT_PATH)));
            if(!Spotify89PanelUtil.BASE_SHA256.equalsIgnoreCase(sha256(wide)))
                return new Validation(false,"Spotify 8.9 wide-screen layout does not match the supported APKM build.");
            for(String p:Spotify89BrandingUtil.DENSITY_ICONS)if(z.getEntry(p)==null)
                return new Validation(false,"Spotify 8.9 launcher icon resource missing from base.apk: "+p);
            if(z.getEntry(Spotify89ResourceUtil.OLD_ADAPTIVE_FOREGROUND_PATH)==null)
                return new Validation(false,"Spotify 8.9 adaptive launcher foreground is missing from base.apk.");

            int dexCount=0,orientationHits=0,pkgHits=0;
            for(Enumeration<? extends ZipEntry> en=z.entries();en.hasMoreElements();){
                ZipEntry e=en.nextElement();String n=e.getName();
                if(n.matches("classes(\\d+)?\\.dex")){
                    dexCount++;byte[] d=IoUtil.readAll(z.getInputStream(e));
                    pkgHits+=Spotify89DexUtil.patchPackage(d,BuildConfigData.V89_PRIMARY_PACKAGE).count;
                    orientationHits+=Spotify89DexUtil.patchRuntimePortraitRequests(d).count;
                }
            }
            if(dexCount!=EXPECTED_BASE_DEX_COUNT||z.getEntry("classes10.dex")!=null)
                return new Validation(false,"Expected Spotify 8.9 APKM base with "+EXPECTED_BASE_DEX_COUNT+" DEX files; found "+dexCount+".");
            if(pkgHits!=EXPECTED_PACKAGE_MARKERS)
                return new Validation(false,"Spotify 8.9 DEX package profile mismatch: expected "+EXPECTED_PACKAGE_MARKERS+", found "+pkgHits+".");
            if(orientationHits!=EXPECTED_RUNTIME_PORTRAIT_MARKERS)
                return new Validation(false,"Spotify 8.9 runtime portrait profile mismatch: expected "+EXPECTED_RUNTIME_PORTRAIT_MARKERS+", found "+orientationHits+".");
            return new Validation(true,"Verified Spotify "+VERSION+" APKM base.apk");
        }catch(Exception e){return new Validation(false,e.getClass().getSimpleName()+": "+e.getMessage());}
    }

    /**
     * Extracts the APKM, patches base + split manifests/resources, signs every selected split,
     * and returns files ready for one PackageInstaller split-APK session.
     */
    static List<File> buildSignedBundle(Context context, File sourceApkm, File outputDir,
                                        boolean secondary, String appName,
                                        Spotify89ResourceUtil.FontPreset fontPreset, boolean rightPanel,
                                        boolean preventPortrait, boolean autoResume, int iconHue, boolean iconBadge,
                                        Progress progress) throws Exception {
        Validation validation=validateSource(sourceApkm);
        if(!validation.ok)throw new IllegalArgumentException(validation.detail);
        String targetPackage=secondary?BuildConfigData.V89_SECONDARY_PACKAGE:BuildConfigData.V89_PRIMARY_PACKAGE;
        String clean=appName==null?"":appName.trim();if(clean.isEmpty())clean=secondary?"SpotifyPlus2":"SpotifyPlus";
        if(clean.codePointCount(0,clean.length())>24)throw new IllegalArgumentException("Spotify 8.9 app name can be up to 24 characters.");

        if(outputDir.exists())deleteTree(outputDir);
        if(!outputDir.mkdirs())throw new IOException("Cannot create Spotify 8.9 APKM build directory");
        File stage=new File(outputDir,".stage");if(!stage.mkdirs())throw new IOException("Cannot create Spotify 8.9 staging directory");
        ResourceTotals totals=new ResourceTotals();

        try(ZipFile bundle=new ZipFile(sourceApkm)){
            List<String> allNames=new ArrayList<>();
            for(Enumeration<? extends ZipEntry> en=bundle.entries();en.hasMoreElements();){String n=en.nextElement().getName();if(n.endsWith(".apk")&&!n.contains("/"))allNames.add(n);}
            String selectedAbi=chooseAbiSplit(allNames);
            List<String> names=new ArrayList<>();
            for(String n:allNames){
                if(isAbiSplit(n)&&!n.equals(selectedAbi))continue;
                names.add(n);
            }
            names.sort((a,b)->{if("base.apk".equals(a))return -1;if("base.apk".equals(b))return 1;return a.compareToIgnoreCase(b);});

            List<File> signed=new ArrayList<>();int index=0;
            for(String name:names){
                index++;
                ZipEntry outer=bundle.getEntry(name);if(outer==null)throw new IOException("APKM entry disappeared: "+name);
                File raw=new File(stage,name+".raw");
                try(InputStream in=new BufferedInputStream(bundle.getInputStream(outer));OutputStream out=new BufferedOutputStream(new FileOutputStream(raw))){IoUtil.copy(in,out);}
                File patched=new File(stage,name+".patched");
                Spotify89ResourceUtil.UiPatchResult rr;
                if("base.apk".equals(name)){
                    progress.onProgress("Patching Spotify 8.9 base.apk…");
                    rr=patchBase(context,raw,patched,targetPackage,clean,fontPreset,rightPanel,preventPortrait,autoResume,iconHue,iconBadge,progress);
                }else{
                    rr=patchSplit(raw,patched,targetPackage,fontPreset);
                }
                totals.add(rr);
                progress.onProgress("Signing Spotify 8.9 APK "+index+"/"+names.size()+" · "+name);
                File signedApk=new File(outputDir,name);
                ApkSignerUtil.sign(patched,signedApk);
                signed.add(signedApk);
                if(!raw.delete())raw.deleteOnExit();if(!patched.delete())patched.deleteOnExit();
            }
            validateResourceTotals(totals,fontPreset);
            deleteTree(stage);
            progress.onProgress("Spotify 8.9 APKM patched · base + "+(names.size()-1)+" compatible splits signed with one local certificate.");
            return signed;
        }catch(Exception e){deleteTree(stage);throw e;}
    }

    private static Spotify89ResourceUtil.UiPatchResult patchBase(Context context, File sourceApk, File output,
                                                                  String targetPackage, String appName,
                                                                  Spotify89ResourceUtil.FontPreset fontPreset,
                                                                  boolean rightPanel, boolean preventPortrait, boolean autoResume,
                                                                  int iconHue, boolean iconBadge,
                                                                  Progress progress) throws Exception {
        Validation validation=validateBaseApk(sourceApk);if(!validation.ok)throw new IllegalArgumentException(validation.detail);
        boolean modifyIcon=iconBadge||IconBrandingUtil.normaliseHue(iconHue)!=0;
        Spotify89ResourceUtil.UiPatchResult uiResult;

        try(ZipFile zin=new ZipFile(sourceApk)){
            byte[] manifest=IoUtil.readAll(zin.getInputStream(zin.getEntry("AndroidManifest.xml")));
            manifest=Spotify89ManifestUtil.patchIdentity(manifest,targetPackage);
            manifest=Spotify89ManifestUtil.patchAutoResume(manifest,targetPackage);
            if(!autoResume)manifest=Spotify89ManifestUtil.disableAutoResumeTrigger(manifest);
            if(preventPortrait){Spotify89ManifestUtil.PortraitResult pr=Spotify89ManifestUtil.patchPortraitOrientations(manifest);if(pr.count!=12)throw new IllegalArgumentException("Expected 12 manifest portrait locks, patched "+pr.count);manifest=pr.data;progress.onProgress("8.9 portrait locks removed from manifest.");}

            byte[] resources=IoUtil.readAll(zin.getInputStream(zin.getEntry("resources.arsc")));
            resources=Spotify89ResourceUtil.patchIdentityAuthority(resources,targetPackage);
            uiResult=Spotify89ResourceUtil.patchUiPart(resources,fontPreset);resources=uiResult.resources;
            Spotify89ResourceUtil.BrandingResult br=Spotify89ResourceUtil.patchBranding(resources,appName,modifyIcon);resources=br.resources;
            progress.onProgress("8.9 wide-screen layout + "+fontPreset.name().toLowerCase(Locale.ROOT)+" font profile applied across APKM resources.");

            byte[] wide=IoUtil.readAll(zin.getInputStream(zin.getEntry(Spotify89PanelUtil.WIDE_LAYOUT_PATH)));
            if(rightPanel){wide=Spotify89PanelUtil.patchRight(wide,targetPackage);progress.onProgress("8.9 Right/RHD panel transform applied.");}
            else progress.onProgress("8.9 Left/LHD panel kept on stock side.");

            Map<String,byte[]> brandedIcons=new HashMap<>();byte[] adaptivePng=null;
            if(modifyIcon){
                for(String p:Spotify89BrandingUtil.DENSITY_ICONS){byte[] raw=IoUtil.readAll(zin.getInputStream(zin.getEntry(p)));brandedIcons.put(p,Spotify89BrandingUtil.brandDensityIcon(raw,iconHue,iconBadge));}
                byte[] xxx=IoUtil.readAll(zin.getInputStream(zin.getEntry(Spotify89BrandingUtil.DENSITY_ICONS[Spotify89BrandingUtil.DENSITY_ICONS.length-1])));
                adaptivePng=Spotify89BrandingUtil.buildAdaptiveForeground(xxx,iconHue,iconBadge);
            }

            byte[] autoDex=Spotify89DexUtil.retargetAutoResumeDex(IoUtil.readAll(context.getAssets().open(AUTO_RESUME_ASSET)),targetPackage);
            if(autoDex.length<512||!contains(autoDex,"AIVI-AutoResume".getBytes(StandardCharsets.US_ASCII)))throw new IOException("Generated robust AutoResume helper DEX is missing or invalid");
            byte[] ltrDex=rightPanel?Spotify89DexUtil.retargetHelperDex(IoUtil.readAll(context.getAssets().open("spotify89/ltr_frame.dex")),targetPackage):null;
            int pkgChanges=0,orientationChanges=0;

            try(CountingOutputStream counted=new CountingOutputStream(new FileOutputStream(output));ZipOutputStream zout=new ZipOutputStream(counted)){
                for(Enumeration<? extends ZipEntry> en=zin.entries();en.hasMoreElements();){
                    ZipEntry src=en.nextElement();String name=src.getName();
                    if(isV1Signature(name))continue;
                    if(modifyIcon&&Spotify89ResourceUtil.OLD_ADAPTIVE_FOREGROUND_PATH.equals(name))continue;
                    byte[] data;
                    if("AndroidManifest.xml".equals(name))data=manifest;
                    else if("resources.arsc".equals(name))data=resources;
                    else if(Spotify89PanelUtil.WIDE_LAYOUT_PATH.equals(name))data=wide;
                    else if(brandedIcons.containsKey(name))data=brandedIcons.get(name);
                    else{
                        data=IoUtil.readAll(zin.getInputStream(src));
                        if(name.matches("classes(\\d+)?\\.dex")){
                            Spotify89DexUtil.PatchResult p=Spotify89DexUtil.patchPackage(data,targetPackage);data=p.data;pkgChanges+=p.count;
                            if(preventPortrait){Spotify89DexUtil.PatchResult q=Spotify89DexUtil.patchRuntimePortraitRequests(data);data=q.data;orientationChanges+=q.count;}
                        }
                    }
                    writeEntry(zout,counted.count,src,data);
                }
                if(modifyIcon&&adaptivePng!=null)writeAdded(zout,Spotify89ResourceUtil.NEW_ADAPTIVE_FOREGROUND_PATH,adaptivePng);
                if(rightPanel){writeAdded(zout,"classes10.dex",ltrDex);writeAdded(zout,"classes11.dex",autoDex);}
                else writeAdded(zout,"classes10.dex",autoDex);
            }
            if(pkgChanges!=EXPECTED_PACKAGE_MARKERS){output.delete();throw new IllegalArgumentException("Expected "+EXPECTED_PACKAGE_MARKERS+" Spotify 8.9 DEX package markers, patched "+pkgChanges);}
            if(preventPortrait&&orientationChanges!=EXPECTED_RUNTIME_PORTRAIT_MARKERS){output.delete();throw new IllegalArgumentException("Expected "+EXPECTED_RUNTIME_PORTRAIT_MARKERS+" runtime portrait requests, patched "+orientationChanges);}
        }
        verifyBaseOutput(sourceApk,output,targetPackage,appName,rightPanel,preventPortrait,autoResume,modifyIcon);
        progress.onProgress(autoResume?"Vehicle restart playback resume enabled · robust retry/session restore helper.":"Vehicle restart playback resume disabled.");
        return uiResult;
    }

    private static Spotify89ResourceUtil.UiPatchResult patchSplit(File source,File output,String targetPackage,
                                                                   Spotify89ResourceUtil.FontPreset fontPreset)throws Exception{
        Spotify89ResourceUtil.UiPatchResult uiResult=null;
        try(ZipFile zin=new ZipFile(source);CountingOutputStream counted=new CountingOutputStream(new FileOutputStream(output));ZipOutputStream zout=new ZipOutputStream(counted)){
            ZipEntry me=zin.getEntry("AndroidManifest.xml");if(me==null)throw new IllegalArgumentException("Spotify 8.9 split manifest missing: "+source.getName());
            byte[] manifest=Spotify89ManifestUtil.patchIdentity(IoUtil.readAll(zin.getInputStream(me)),targetPackage);
            ZipEntry re=zin.getEntry("resources.arsc");byte[] patchedResources=null;
            if(re!=null){uiResult=Spotify89ResourceUtil.patchUiPart(IoUtil.readAll(zin.getInputStream(re)),fontPreset);patchedResources=uiResult.resources;}
            for(Enumeration<? extends ZipEntry> en=zin.entries();en.hasMoreElements();){
                ZipEntry src=en.nextElement();String name=src.getName();if(isV1Signature(name))continue;
                byte[] data;
                if("AndroidManifest.xml".equals(name))data=manifest;
                else if("resources.arsc".equals(name)&&patchedResources!=null)data=patchedResources;
                else data=IoUtil.readAll(zin.getInputStream(src));
                writeEntry(zout,counted.count,src,data);
            }
        }
        verifySplitOutput(output,targetPackage);
        return uiResult;
    }

    private static void validateResourceTotals(ResourceTotals totals,Spotify89ResourceUtil.FontPreset preset){
        if(totals.visual!=EXPECTED_APKM_VISUAL_CHANGES)throw new IllegalArgumentException(
                "Spotify 8.9 APKM visual profile mismatch: expected "+EXPECTED_APKM_VISUAL_CHANGES+", patched "+totals.visual+".");
        if(preset==Spotify89ResourceUtil.FontPreset.MODERATE||preset==Spotify89ResourceUtil.FontPreset.LARGE){
            if(totals.moderate!=EXPECTED_APKM_FONT_CHANGES)throw new IllegalArgumentException(
                    "Spotify 8.9 APKM font profile mismatch: expected "+EXPECTED_APKM_FONT_CHANGES+" moderate values, patched "+totals.moderate+".");
            if(preset==Spotify89ResourceUtil.FontPreset.LARGE&&totals.large!=EXPECTED_APKM_FONT_CHANGES)throw new IllegalArgumentException(
                    "Spotify 8.9 APKM large-font profile mismatch: expected "+EXPECTED_APKM_FONT_CHANGES+", patched "+totals.large+".");
        }
    }

    private static String chooseAbiSplit(List<String> names){
        for(String abi:Build.SUPPORTED_ABIS){String candidate="split_config."+abi.replace('-','_')+".apk";if(names.contains(candidate))return candidate;}
        throw new IllegalArgumentException("The Spotify 8.9 APKM has no ABI split compatible with this device. Device ABIs: "+Arrays.toString(Build.SUPPORTED_ABIS));
    }

    private static boolean isAbiSplit(String name){return "split_config.arm64_v8a.apk".equals(name)||"split_config.x86.apk".equals(name);}

    private static void verifyBaseOutput(File source,File output,String targetPackage,String appLabel,
                                         boolean rightPanel,boolean preventPortrait,boolean autoResume,boolean modifiedIcon)throws Exception{
        if(!output.isFile()||output.length()<1024)throw new IOException("Spotify 8.9 patched base.apk was not created correctly");
        try(ZipFile src=new ZipFile(source);ZipFile out=new ZipFile(output)){
            if(!serviceHashes(src).equals(serviceHashes(out)))throw new IllegalArgumentException("META-INF/services runtime files were not preserved exactly");
            byte[] manifest=IoUtil.readAll(out.getInputStream(out.getEntry("AndroidManifest.xml")));List<String> ms=Spotify89ManifestUtil.scanStrings(manifest);
            if(!ms.contains(targetPackage)||ms.contains(Spotify89ManifestUtil.OLD_PACKAGE))throw new IllegalArgumentException("8.9 output package identity verification failed");
            String restoreAction=autoResume?"byd.intent.action.RESTORE_PLAYBACK":"byd.intent.action.RESTORE_DISABLED";
            if(!ms.contains(targetPackage+".AutoResumeService")||!ms.contains(restoreAction))throw new IllegalArgumentException("8.9 AutoResume manifest verification failed");
            if(preventPortrait&&Spotify89ManifestUtil.patchPortraitOrientations(manifest).count!=0)throw new IllegalArgumentException("Explicit 8.9 portrait manifest locks remain");

            byte[] resources=IoUtil.readAll(out.getInputStream(out.getEntry("resources.arsc")));Spotify89BinaryUtil.Pool gp=Spotify89BinaryUtil.parsePool(resources,Spotify89BinaryUtil.u16(resources,2));
            if(!gp.strings.contains(appLabel))throw new IllegalArgumentException("8.9 app label verification failed");
            if(modifiedIcon&&!gp.strings.contains(Spotify89ResourceUtil.NEW_ADAPTIVE_FOREGROUND_PATH))throw new IllegalArgumentException("8.9 launcher branding verification failed");
            if(!modifiedIcon&&out.getEntry(Spotify89ResourceUtil.OLD_ADAPTIVE_FOREGROUND_PATH)==null)throw new IllegalArgumentException("Original 8.9 launcher foreground was not preserved");

            byte[] sourceWide=IoUtil.readAll(src.getInputStream(src.getEntry(Spotify89PanelUtil.WIDE_LAYOUT_PATH)));
            byte[] expectedWide=rightPanel?Spotify89PanelUtil.patchRight(sourceWide,targetPackage):sourceWide;
            byte[] actualWide=IoUtil.readAll(out.getInputStream(out.getEntry(Spotify89PanelUtil.WIDE_LAYOUT_PATH)));
            if(!Arrays.equals(expectedWide,actualWide))throw new IllegalArgumentException("8.9 wide-screen layout verification failed");

            int dexCount=0;
            for(Enumeration<? extends ZipEntry> en=out.entries();en.hasMoreElements();){
                ZipEntry e=en.nextElement();String n=e.getName();if(!n.matches("classes(\\d+)?\\.dex"))continue;dexCount++;
                byte[] d=IoUtil.readAll(out.getInputStream(e));if(!Arrays.equals(d,DexUtil.repair(d)))throw new IllegalArgumentException("DEX checksum verification failed: "+n);
                if(Spotify89DexUtil.patchPackage(d,targetPackage).count!=0)throw new IllegalArgumentException("Old exact com.spotify.music DEX marker remains: "+n);
                if(preventPortrait&&Spotify89DexUtil.patchRuntimePortraitRequests(d).count!=0)throw new IllegalArgumentException("Runtime portrait request remains: "+n);
            }
            int expectedDex=rightPanel?11:10;if(dexCount!=expectedDex)throw new IllegalArgumentException("Expected "+expectedDex+" output DEX files, found "+dexCount);
            String resumeDexName=rightPanel?"classes11.dex":"classes10.dex";
            ZipEntry resumeEntry=out.getEntry(resumeDexName);
            if(resumeEntry==null||!contains(IoUtil.readAll(out.getInputStream(resumeEntry)),"AIVI-AutoResume".getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException("8.9 robust AutoResume helper verification failed");
        }
    }

    private static void verifySplitOutput(File output,String targetPackage)throws Exception{
        try(ZipFile z=new ZipFile(output)){
            ZipEntry me=z.getEntry("AndroidManifest.xml");if(me==null)throw new IllegalArgumentException("Patched split manifest missing");
            List<String> ms=Spotify89ManifestUtil.scanStrings(IoUtil.readAll(z.getInputStream(me)));
            if(!ms.contains(targetPackage)||ms.contains(Spotify89ManifestUtil.OLD_PACKAGE))throw new IllegalArgumentException("Spotify 8.9 split package identity verification failed: "+output.getName());
        }
    }

    private static boolean isV1Signature(String name){String u=name.toUpperCase(Locale.ROOT);if("META-INF/MANIFEST.MF".equals(u))return true;return u.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)");}

    private static final class CountingOutputStream extends FilterOutputStream{
        long count;CountingOutputStream(OutputStream out){super(out);}@Override public void write(int b)throws IOException{out.write(b);count++;}@Override public void write(byte[]b,int off,int len)throws IOException{out.write(b,off,len);count+=len;}
    }

    private static void writeAdded(ZipOutputStream z,String name,byte[]data)throws IOException{ZipEntry e=new ZipEntry(name);e.setMethod(ZipEntry.DEFLATED);z.putNextEntry(e);z.write(data);z.closeEntry();}

    /** Preserve 4-byte alignment for uncompressed APK entries. */
    private static void writeEntry(ZipOutputStream z,long currentOffset,ZipEntry t,byte[]data)throws IOException{
        ZipEntry e=new ZipEntry(t.getName());e.setTime(t.getTime());e.setComment(t.getComment());
        byte[] extra=t.getExtra();if(extra==null)extra=new byte[0];else extra=extra.clone();
        int m=t.getMethod();if(m!=ZipEntry.STORED&&m!=ZipEntry.DEFLATED)m=ZipEntry.DEFLATED;e.setMethod(m);
        if(m==ZipEntry.STORED){
            byte[] name=e.getName().getBytes(StandardCharsets.UTF_8);long base=currentOffset+30L+name.length+extra.length;int needed=(int)((-base)&3L);
            if(needed!=0){byte[] aligned=Arrays.copyOf(extra,extra.length+4+needed);int p=extra.length;aligned[p]=(byte)0x35;aligned[p+1]=(byte)0xD9;aligned[p+2]=(byte)needed;aligned[p+3]=0;extra=aligned;}
            CRC32 c=new CRC32();c.update(data);e.setSize(data.length);e.setCompressedSize(data.length);e.setCrc(c.getValue());
        }
        if(extra.length>0)e.setExtra(extra);z.putNextEntry(e);z.write(data);z.closeEntry();
    }

    private static Map<String,String> serviceHashes(ZipFile z)throws Exception{
        Map<String,String> m=new TreeMap<>();for(Enumeration<? extends ZipEntry> en=z.entries();en.hasMoreElements();){ZipEntry e=en.nextElement();if(e.getName().startsWith("META-INF/services/"))m.put(e.getName(),sha256(IoUtil.readAll(z.getInputStream(e))));}return m;
    }

    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] kids=f.listFiles();if(kids!=null)for(File k:kids)deleteTree(k);}if(!f.delete())f.deleteOnExit();}
    private static String sha256(byte[]data)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[]h=md.digest(data);StringBuilder s=new StringBuilder();for(byte b:h)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString();}
    private static int count(byte[]h,byte[]n){int c=0;for(int i=0;i+n.length<=h.length;){boolean ok=true;for(int j=0;j<n.length;j++)if(h[i+j]!=n[j]){ok=false;break;}if(ok){c++;i+=n.length;}else i++;}return c;}
    private static boolean contains(byte[]h,byte[]n){return count(h,n)>0;}
}
