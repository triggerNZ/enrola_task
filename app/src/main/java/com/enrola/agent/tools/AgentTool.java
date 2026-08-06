package com.enrola.agent.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/**
 * Marks a class holding methods the model may call. {@link ToolRegistry} finds every bean
 * annotated with this and offers its {@link dev.langchain4j.agent.tool.Tool} methods to the model.
 *
 * <p>Also a Spring stereotype, so a tool needs this annotation and nothing else: no
 * {@code @Component}, no interface to implement, and no registration anywhere.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface AgentTool {}
