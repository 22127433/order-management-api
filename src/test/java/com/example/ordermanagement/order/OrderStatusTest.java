package com.example.ordermanagement.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusTest {
    @ParameterizedTest(name = "{0} -> {1}: allowed={2}")
    @MethodSource("transitionMatrix")
    void acceptsOnlyTheFourBusinessTransitions(OrderStatus source, OrderStatus target, boolean allowed) {
        assertThat(source.canTransitionTo(target)).isEqualTo(allowed);
    }

    static Stream<Arguments> transitionMatrix() {
        Set<String> allowed = Set.of("CREATED:CONFIRMED", "CONFIRMED:SHIPPED",
                "SHIPPED:DELIVERED", "CREATED:CANCELLED");
        return Arrays.stream(OrderStatus.values()).flatMap(source -> Arrays.stream(OrderStatus.values())
                .map(target -> Arguments.of(source, target, allowed.contains(source + ":" + target))));
    }
}
