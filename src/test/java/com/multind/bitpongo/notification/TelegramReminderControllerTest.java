package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TelegramReminderControllerTest {
    private final TelegramReminderService service = mock(TelegramReminderService.class);
    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramReminderController controller = new TelegramReminderController(service, client);
    private final ObjectMapper json = new ObjectMapper();
    private final String code = "abcdefghijklmnopqrstuvwxyzABCDEF";

    @Test void rejectsUnauthenticatedUpdatesAndIgnoresNonPrivateChats() {
        assertThatThrownBy(() -> controller.webhook("wrong", json.createObjectNode())).isInstanceOf(BusinessException.class);
        when(client.authentic("valid")).thenReturn(true);
        controller.webhook("valid", update("group", 123));
        controller.webhook("valid", update("private", 456));
        verifyNoInteractions(service);
    }

    @Test void bindsAnAuthenticatedPrivateStartToTheSenderChat() {
        when(client.authentic("valid")).thenReturn(true);
        controller.webhook("valid", update("private", 123));
        verify(service).bind(code, 123L);
    }

    private tools.jackson.databind.JsonNode update(String type, long sender) {
        return json.readTree("""
            {"message":{"chat":{"type":"%s","id":123},"from":{"id":%d},"text":"/start %s"}}
            """.formatted(type, sender, code));
    }
}
