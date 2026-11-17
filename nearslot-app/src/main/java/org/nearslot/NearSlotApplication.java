package org.nearslot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** NearSlot 唯一可执行入口（ADR-001：app 唯一聚合所有模块）。 */
@SpringBootApplication(scanBasePackages = "org.nearslot")
@MapperScan("org.nearslot.**.mapper")
@EnableScheduling
public class NearSlotApplication {
    public static void main(String[] args) { SpringApplication.run(NearSlotApplication.class, args); }
}
