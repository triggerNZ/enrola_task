package com.enrola.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Everything the model may call, gathered from the {@link AgentTool} beans.
 *
 * <p>The specification the model sees and the code that runs both come from the same annotated
 * method: langchain4j derives the JSON schema from the signature and {@code @P} descriptions, and
 * {@link DefaultToolExecutor} binds the model's arguments back onto the parameters. Writing both
 * by hand is how a tool's description drifts from what it actually does.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final List<ToolSpecification> specifications;
    private final Map<String, ToolExecutor> executors;

    // Explicit, because the private constructor below makes two, and Spring will not guess.
    @Autowired
    ToolRegistry(ApplicationContext context) {
        this(context.getBeansWithAnnotation(AgentTool.class).values());
    }

    /** For tests, which build a registry from a few tools without a Spring context. */
    public static ToolRegistry of(Object... tools) {
        return new ToolRegistry(List.of(tools));
    }

    private ToolRegistry(Collection<Object> tools) {
        List<ToolSpecification> specifications = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();

        for (Object tool : tools) {
            // The target class, not the bean's: a proxied bean hides the annotated methods.
            for (Method method : AopUtils.getTargetClass(tool).getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
                ToolExecutor previous =
                        executors.put(specification.name(), new DefaultToolExecutor(tool, method));
                if (previous != null) {
                    throw new IllegalStateException("Two tools are named " + specification.name() + ".");
                }
                specifications.add(specification);
            }
        }

        this.specifications = List.copyOf(specifications);
        this.executors = Map.copyOf(executors);
        log.info("Tools available to the agent: {}", this.executors.keySet());
    }

    /** What the model is told it can do. */
    public List<ToolSpecification> specifications() {
        return specifications;
    }

    /** Empty when the model asks for a tool that does not exist, which it sometimes does. */
    public Optional<ToolExecutor> find(String name) {
        return Optional.ofNullable(executors.get(name));
    }
}
