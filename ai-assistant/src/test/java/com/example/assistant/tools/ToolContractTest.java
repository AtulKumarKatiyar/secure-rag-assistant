package com.example.assistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the contract that Spring AI 1.0.0's {@code MethodToolCallbackProvider} requires.
 *
 * <p>{@code isFunctionalType} rejects a {@code @Tool} method when the return type is assignable
 * <em>from</em> {@code Function}, {@code Supplier} or {@code Consumer}. Every class is assignable
 * from those, including {@code Object}, so a tool method declared as
 * {@code public Object getFoo(...)} is silently dropped with only a WARN log. When all four
 * market-data tools returned {@code Object}, no agent could be constructed and the application
 * failed to start.
 *
 * <p>These tests fail loudly instead of leaving that to a runtime warning.
 */
class ToolContractTest {

    private static final List<Class<?>> TOOL_COMPONENTS = List.of(
            RagSearchTool.class,
            PolicySearchTool.class,
            StockNewsTool.class,
            IndexManagementTool.class,
            CommodityDataTool.class);

    @Test
    void everyToolComponentExposesAtLeastOneToolMethod() {
        for (Class<?> toolComponent : TOOL_COMPONENTS) {
            assertThat(toolMethodsOf(toolComponent))
                    .as("@Tool methods on %s", toolComponent.getSimpleName())
                    .isNotEmpty();
        }
    }

    @Test
    void noToolMethodWouldBeDiscardedAsAFunctionalType() {
        for (Class<?> toolComponent : TOOL_COMPONENTS) {
            for (Method method : toolMethodsOf(toolComponent)) {
                assertThat(springAiWouldDiscard(method))
                        .as("%s.%s returns %s, which Spring AI drops; return a JSON String instead",
                                toolComponent.getSimpleName(), method.getName(), method.getReturnType())
                        .isFalse();
            }
        }
    }

    @Test
    void toolMethodsReturnJsonText() {
        for (Class<?> toolComponent : TOOL_COMPONENTS) {
            for (Method method : toolMethodsOf(toolComponent)) {
                assertThat(method.getReturnType())
                        .as("%s.%s should return String JSON", toolComponent.getSimpleName(), method.getName())
                        .isEqualTo(String.class);
            }
        }
    }

    private static List<Method> toolMethodsOf(Class<?> toolComponent) {
        return List.of(ReflectionUtils.getDeclaredMethods(toolComponent)).stream()
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .toList();
    }

    /**
     * Mirrors {@code MethodToolCallbackProvider#isFunctionalType} in Spring AI 1.0.0, including its
     * argument order.
     */
    private static boolean springAiWouldDiscard(Method method) {
        Class<?> returnType = method.getReturnType();
        return ClassUtils.isAssignable(returnType, Function.class)
                || ClassUtils.isAssignable(returnType, Supplier.class)
                || ClassUtils.isAssignable(returnType, Consumer.class);
    }
}
