package com.multind.bitpongo.alerts;
import com.multind.bitpongo.auth.JwtTokenService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@AutoConfigureMockMvc
class UserAvatarIntegrationTest extends PriceAlertIntegrationTest {
    @Autowired MockMvc mvc;@Autowired JwtTokenService tokens;
    @Test void uploadPersistsOnlyForCurrentAccountAndCanBeRemoved()throws Exception {
        String auth="Bearer "+tokens.issue(user);
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(300,200,BufferedImage.TYPE_INT_RGB),"png",out);
        mvc.perform(multipart("/api/users/profile/avatar").file(new MockMultipartFile("file","../../image.png","image/png",out.toByteArray())).header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.imageData").isNotEmpty());
        assertThat(count("user_avatar")).isEqualTo(1);
        mvc.perform(get("/api/users/profile/avatar").header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.imageData").isNotEmpty());
        jdbc.update("insert into user(name,email,password) values('other',?,'unused')",java.util.UUID.randomUUID()+"@example.com");
        long other=jdbc.queryForObject("select last_insert_id()",Long.class);
        mvc.perform(get("/api/users/profile/avatar").header("Authorization","Bearer "+tokens.issue(other))).andExpect(status().isOk()).andExpect(jsonPath("$.data.imageData").isEmpty());
        mvc.perform(delete("/api/users/profile/avatar").header("Authorization",auth)).andExpect(status().isOk());
        assertThat(count("user_avatar")).isZero();
    }
    @Test void invalidUploadDoesNotReplaceExistingAvatarAndAccountDeletionCleansIt()throws Exception {
        String auth="Bearer "+tokens.issue(user);
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB),"png",out);
        mvc.perform(multipart("/api/users/profile/avatar").file(new MockMultipartFile("file","x.png","image/png",out.toByteArray())).header("Authorization",auth)).andExpect(status().isOk());
        byte[] original=jdbc.queryForObject("select image from user_avatar where user_id=?",byte[].class,user);
        mvc.perform(multipart("/api/users/profile/avatar").file(new MockMultipartFile("file","x.png","image/png","not an image".getBytes())).header("Authorization",auth)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select image from user_avatar where user_id=?",byte[].class,user)).isEqualTo(original);
        jdbc.update("update user set password=? where id=?",passwords.hash("Delete-me-123"),user);accounts.delete(user,"Delete-me-123");
        assertThat(count("user_avatar")).isZero();
        mvc.perform(get("/api/users/profile/avatar")).andExpect(status().isUnauthorized());
    }
}
