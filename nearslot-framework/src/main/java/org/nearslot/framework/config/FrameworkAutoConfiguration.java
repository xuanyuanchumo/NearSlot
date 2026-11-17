package org.nearslot.framework.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** framework 自动装配入口（app 通过 scanBasePackages 装载）。 */
@Configuration
@ComponentScan(basePackages = "org.nearslot.framework")
public class FrameworkAutoConfiguration {
}
