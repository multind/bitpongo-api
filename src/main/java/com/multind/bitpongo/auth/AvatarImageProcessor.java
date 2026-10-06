package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Re-encodes uploaded images; never persists original bytes or metadata. */
public final class AvatarImageProcessor {
    private AvatarImageProcessor() {}

    public static byte[] normalize(byte[] upload) {
        if (upload == null || upload.length == 0 || upload.length > 2 * 1024 * 1024) {
            throw invalid();
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(upload))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw invalid();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096) throw invalid();
                int side = Math.min(width, height);
                var parameters = reader.getDefaultReadParam();
                parameters.setSourceRegion(new Rectangle((width-side)/2, (height-side)/2, side, side));
                int sampling = Math.max(1, side / 256);
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                var source = reader.read(0, parameters);
                var output = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                var graphics = output.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(source, 0, 0, 256, 256, null);
                } finally { graphics.dispose(); }
                var bytes = new ByteArrayOutputStream();
                if (!ImageIO.write(output, "png", bytes) || bytes.size() > 300 * 1024) throw invalid();
                return bytes.toByteArray();
            } finally { reader.dispose(); }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(400, "头像必须为不超过 2 MB、4096 像素的 PNG 或 JPEG 图片");
    }
}
