package com.multind.bitpongo.content;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AppVersionServiceTest {
    private AppVersionService service(String version,String build,String url){return new AppVersionService(version,build,url,"","","","");}
    @Test void missingReleaseIsUnavailableRatherThanPretendingToBeLatest(){
        assertThat(service("","","").check("ios","1.0.1",15).available()).isFalse();
        assertThat(service("","","").check("ios","1.0.1",15).updateAvailable()).isFalse();
    }
    @Test void comparesNumericVersionsAndBuildsWithoutDowngrades(){
        var service=service("1.0.10","20","https://apps.apple.com/app/id6473227759");
        assertThat(service.check("ios","1.0.9",99).updateAvailable()).isTrue();
        assertThat(service.check("ios","1.0.10",19).updateAvailable()).isTrue();
        assertThat(service.check("ios","1.0.10",20).updateAvailable()).isFalse();
        assertThat(service.check("ios","1.1.0",1).updateAvailable()).isFalse();
    }
    @Test void refusesInvalidClientMetadataOrUnsafeStoreLinks(){
        assertThatThrownBy(()->service("1.0.10","20","https://apps.apple.com/app/id6473227759").check("other","1.0.0",1)).hasMessageContaining("版本");
        assertThatThrownBy(()->service("1.0.10","20","https://apps.apple.com/app/id6473227759").check("ios","1.x",1)).hasMessageContaining("版本");
        assertThatThrownBy(()->service("1.0.10","20","https://apps.apple.com/app/id6473227759").check("ios","1.0.0",-1)).hasMessageContaining("版本");
        assertThat(service("1.0.10","20","javascript:alert(1)").check("ios","1.0.0",1).available()).isFalse();
        assertThat(service("1.0.10","20","https://apps.apple.com.attacker.invalid/app").check("ios","1.0.0",1).available()).isFalse();
    }
}
