package com.multind.bitpongo.content;

import com.multind.bitpongo.common.api.BusinessException;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Service
public class AppVersionService {
    private final String iosVersion,iosBuild,iosUrl,androidVersion,androidBuild,androidUrl,notes;
    public AppVersionService(@Value("${BITPONGO_IOS_LATEST_VERSION:}")String iosVersion,
        @Value("${BITPONGO_IOS_LATEST_BUILD:}")String iosBuild,@Value("${BITPONGO_IOS_STORE_URL:}")String iosUrl,
        @Value("${BITPONGO_ANDROID_LATEST_VERSION:}")String androidVersion,@Value("${BITPONGO_ANDROID_LATEST_BUILD:}")String androidBuild,
        @Value("${BITPONGO_ANDROID_STORE_URL:}")String androidUrl,@Value("${BITPONGO_APP_RELEASE_NOTES:}")String notes){
        this.iosVersion=iosVersion;this.iosBuild=iosBuild;this.iosUrl=iosUrl;
        this.androidVersion=androidVersion;this.androidBuild=androidBuild;this.androidUrl=androidUrl;this.notes=notes;
    }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Release(boolean available,boolean updateAvailable,String latestVersion,int latestBuild,String storeUrl,String releaseNotes){}
    public Release check(String platform,String version,Integer build){
        if(!List.of("ios","android").contains(platform) || !validVersion(version) || (build!=null && build<0))throw new BusinessException(400,"版本参数无效");
        String latest=platform.equals("ios")?iosVersion:androidVersion;
        String configuredBuild=platform.equals("ios")?iosBuild:androidBuild;
        String url=platform.equals("ios")?iosUrl:androidUrl;
        if(!validVersion(latest) || !trustedStore(platform,url))return unavailable();
        int latestBuild;
        try{latestBuild=Integer.parseInt(configuredBuild);if(latestBuild<1)return unavailable();}catch(NumberFormatException e){return unavailable();}
        int compared=compare(latest,version);
        return new Release(true,compared>0 || (compared==0 && build!=null && latestBuild>build),latest,latestBuild,url,notes==null?"":notes.substring(0,Math.min(notes.length(),2000)));
    }
    private static Release unavailable(){return new Release(false,false,"",0,"","");}
    private static boolean validVersion(String version){return version!=null && version.matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}");}
    private static int compare(String left,String right){
        String[] a=left.split("\\."),b=right.split("\\.");
        for(int i=0;i<3;i++){int value=Integer.compare(Integer.parseInt(a[i]),Integer.parseInt(b[i]));if(value!=0)return value;}return 0;
    }
    private static boolean trustedStore(String platform,String url){
        try{
            URI uri=URI.create(url);
            if(!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getFragment()!=null)return false;
            if(platform.equals("ios"))return "apps.apple.com".equals(uri.getHost()) && uri.getPath()!=null && uri.getPath().matches("/(?:[a-z]{2}/)?app/(?:[^/]+/)?id6473227759");
            return "play.google.com".equals(uri.getHost()) && "/store/apps/details".equals(uri.getPath()) && "id=io.imastermind.bitpongo".equals(uri.getQuery());
        }catch(IllegalArgumentException e){return false;}
    }
}
