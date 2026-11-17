package org.nearslot.appointment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 抢约请求。 */
@Data
public class BookRequest {
    @NotNull(message = "slotId 不能为空")
    private Long slotId;
}
