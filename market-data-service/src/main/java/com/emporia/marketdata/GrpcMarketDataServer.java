package com.emporia.marketdata;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
final class GrpcMarketDataServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GrpcMarketDataServer.class);

    private final GrpcMarketDataService service;
    private final boolean enabled;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Server server;

    GrpcMarketDataServer(GrpcMarketDataService service,
                         @Value("${emporia.market-data.grpc.enabled:true}") boolean enabled,
                         @Value("${emporia.market-data.grpc.port:50551}") int port) {
        this.service = service;
        this.enabled = enabled;
        this.port = port;
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            server = NettyServerBuilder.forPort(port).addService(service).build().start();
            log.info("Go-compatible market-data gRPC endpoint listening on port {}", server.getPort());
        } catch (IOException error) {
            running.set(false);
            throw new IllegalStateException("Unable to start market-data gRPC endpoint on port " + port, error);
        }
    }

    @Override
    public void stop() {
        Server current = server;
        server = null;
        if (current != null) {
            current.shutdown();
            try {
                if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                    current.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                current.shutdownNow();
            }
        }
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 50;
    }
}
