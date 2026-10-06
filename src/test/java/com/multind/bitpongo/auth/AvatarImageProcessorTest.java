package com.multind.bitpongo.auth;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AvatarImageProcessorTest {
    static byte[] png(int width,int height)throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        image.setRGB(width/2,height/2,0x00ff00);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    @Test void normalizesRealImagesToSmallPngAndStripsTrailingUploadData()throws Exception {
        byte[] source=png(400,300),withTail=java.util.Arrays.copyOf(source,source.length+20);
        byte[] result=AvatarImageProcessor.normalize(withTail);
        var image=ImageIO.read(new java.io.ByteArrayInputStream(result));
        assertThat(image.getWidth()).isEqualTo(256);assertThat(image.getHeight()).isEqualTo(256);
        assertThat(result).startsWith((byte)137,(byte)80,(byte)78,(byte)71);
        assertThat(result.length).isLessThan(300*1024);
    }
    @Test void rejectsNonImagesHugeDimensionsAndOversizedUploads()throws Exception {
        assertThatThrownBy(()->AvatarImageProcessor.normalize("<svg onload='alert(1)'/>".getBytes())).hasMessageContaining("头像");
        assertThatThrownBy(()->AvatarImageProcessor.normalize(new byte[2*1024*1024+1])).hasMessageContaining("头像");
        assertThatThrownBy(()->AvatarImageProcessor.normalize(png(4097,1))).hasMessageContaining("头像");
    }
}
