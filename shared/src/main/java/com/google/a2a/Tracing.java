package com.google.a2a;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import com.google.cloud.opentelemetry.trace.TraceExporter;

public class Tracing {
    private static Tracer tracer;

    public static synchronized Tracer init(String serviceName) {
        if (tracer != null) return tracer;
        try {
            SpanExporter exporter = TraceExporter.createWithDefaultConfiguration();
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();
            tracer = sdk.getTracer(serviceName);
        } catch (Exception e) {
            System.err.println("Could not initialize Cloud Trace Exporter for " + serviceName + ": " + e.getMessage());
            tracer = GlobalOpenTelemetry.getTracer(serviceName);
        }
        return tracer;
    }

    public static Tracer getTracer() {
        return tracer != null ? tracer : GlobalOpenTelemetry.getTracer("default");
    }
}
