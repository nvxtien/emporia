package com.emporia.staticdata;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditServiceTest {

    private final AdminAuditEventRepository repository = mock(AdminAuditEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AdminAuditService service = new AdminAuditService(repository, objectMapper);

    @Test
    void recordSavesAuditEvent() {
        AdminAuditContext context = new AdminAuditContext("sub-1", "user-1", "desk-1", "req-1");
        service.record(context, "INSTRUMENT_CREATED", "INSTRUMENT", "100", null, Map.of("symbol", "AAPL"), null);

        verify(repository).save(any(AdminAuditEvent.class));
    }

    @Test
    void listReturnsAdminAuditPage() {
        AdminAuditContext context = new AdminAuditContext("sub-1", "user-1", "desk-1", "req-1");
        AdminAuditEvent event = new AdminAuditEvent(context, "INSTRUMENT_CREATED", "INSTRUMENT", "100", "SUCCESS", null, "{}", null);
        Page<AdminAuditEvent> mockPage = new PageImpl<>(List.of(event), PageRequest.of(0, 50), 1);

        when(repository.findForAdmin(eq(""), eq(""), eq(""), eq(""), eq(""), any())).thenReturn(mockPage);

        AdminAuditService.AdminAuditPage result = service.list(null);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().action()).isEqualTo("INSTRUMENT_CREATED");
    }
}
