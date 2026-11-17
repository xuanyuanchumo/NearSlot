package org.nearslot.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.nearslot.appointment.entity.TimeSlot;

@Mapper
public interface TimeSlotMapper extends BaseMapper<TimeSlot> {
}
