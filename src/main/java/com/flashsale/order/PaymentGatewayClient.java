package com.flashsale.order;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@Slf4j
public class PaymentGatewayClient {

    /**
     * Executes external credit card capture with Resilience4j Circuit Breaker protection.
     * If the payment provider becomes degraded or times out, the circuit opens to prevent resource exhaustion.
     */
    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "paymentFallback")
    public String processPayment(UUID orderId, BigDecimal amount, String paymentMethodToken) {
        log.info("Dispatching capture to Payment Gateway: orderId={}, amount={}, token={}", orderId, amount, paymentMethodToken);

        // Simulation: Fail if test token is 'pm_fail'
        if ("pm_fail".equals(paymentMethodToken)) {
            throw new RuntimeException("Payment processor declined transaction: Insufficient funds or card declined");
        }

        // Return payment reference
        String paymentReference = "pay_ref_" + UUID.randomUUID().toString().substring(0, 12);
        log.info("Payment captured successfully: reference={}", paymentReference);
        return paymentReference;
    }

    public String paymentFallback(UUID orderId, BigDecimal amount, String paymentMethodToken, Throwable t) {
        log.error("Payment Gateway Circuit Breaker triggered or call failed: {}", t.getMessage());
        throw new RuntimeException("Payment processing currently unavailable: " + t.getMessage(), t);
    }
}
