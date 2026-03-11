package demo.adtech;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class BenchmarkSettings {

    public static final String DELIVERY_MODE_CONFIRM = "confirm";
    public static final String DELIVERY_MODE_ENQUEUE = "enqueue";
    public static final String DELIVERY_MODE_HTTP_ONLY = "http-only";

    @ConfigProperty(name = "benchmark.delivery.mode", defaultValue = "confirm")
    String deliveryMode;

    public String deliveryMode() {
        if (deliveryMode == null) {
            return DELIVERY_MODE_CONFIRM;
        }
        return switch (deliveryMode.trim().toLowerCase()) {
            case DELIVERY_MODE_ENQUEUE -> DELIVERY_MODE_ENQUEUE;
            case DELIVERY_MODE_HTTP_ONLY -> DELIVERY_MODE_HTTP_ONLY;
            case DELIVERY_MODE_CONFIRM -> DELIVERY_MODE_CONFIRM;
            default -> DELIVERY_MODE_CONFIRM;
        };
    }

    public boolean isHttpOnlyMode() {
        return DELIVERY_MODE_HTTP_ONLY.equals(deliveryMode());
    }

    public boolean isConfirmDeliveryMode() {
        return DELIVERY_MODE_CONFIRM.equals(deliveryMode());
    }
}
