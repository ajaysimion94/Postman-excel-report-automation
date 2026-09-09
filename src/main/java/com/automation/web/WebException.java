package com.automation.web;

final class WebException extends RuntimeException {
    final int status;

    WebException(int status, String message) {
        super(message);
        this.status = status;
    }
}
