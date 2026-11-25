package com.sparrowwallet.nightjar.stomp;

import com.samourai.stomp.client.IStompMessage;

import java.util.Collections;
import java.util.Map;

public class JavaStompMessage implements IStompMessage {
    private final Map<String, String> headers;
    private final Object payload;

    public JavaStompMessage(Map<String, String> headers, Object payload) {
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.payload = payload;
    }

    @Override
    public String getStompHeader(String headerName) {
        return headers.get(headerName);
    }

    @Override
    public Object getPayload() {
        return payload;
    }
}
