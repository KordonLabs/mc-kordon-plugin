package com.kordonlabs.plugin.common.api.dto;

/** The panel's error envelope {@code {"error":{"code","message"}}} sent with every non-200 answer. */
public record ErrorBody(Detail error) {

    public record Detail(String code, String message) {
    }
}
