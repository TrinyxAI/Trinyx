package com.apimarketplace.common.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Repeatable servlet request body used by HMAC v2 verification. */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyHttpServletRequest(HttpServletRequest request, int maximumBytes) throws IOException {
        super(request);
        int limit = Math.max(0, maximumBytes);
        this.body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            throw new BodyTooLargeException(limit);
        }
    }

    byte[] body() {
        return body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener readListener) {
                if (readListener == null) return;
                try {
                    if (isFinished()) readListener.onAllDataRead();
                    else readListener.onDataAvailable();
                } catch (IOException e) {
                    readListener.onError(e);
                }
            }
            @Override public int read() { return input.read(); }
            @Override public int read(byte[] target, int offset, int length) {
                return input.read(target, offset, length);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(int maximumBytes) {
            super("Request body exceeds gateway.filter.max-body-bytes=" + maximumBytes);
        }
    }
}
