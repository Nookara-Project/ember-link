package net.ikelene.bonfire.fabric;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import io.netty.buffer.ByteBuf;

public final class Payloads {
    private Payloads() {
    }

    public record Events(String json) implements CustomPacketPayload {
        public static final Type<Events> TYPE = new Type<>(Identifier.fromNamespaceAndPath("bonfire", "events"));
        public static final StreamCodec<ByteBuf, Events> CODEC = ByteBufCodecs.STRING_UTF8.map(Events::new, Events::json);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Hello(String json) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(Identifier.fromNamespaceAndPath("bonfire", "hello"));
        public static final StreamCodec<ByteBuf, Hello> CODEC = ByteBufCodecs.STRING_UTF8.map(Hello::new, Hello::json);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
