/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.eclipse.jetty.server.handler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;

import com.newrelic.agent.bridge.AgentBridge;
import com.newrelic.api.agent.weaver.NewField;
import com.newrelic.api.agent.weaver.Weave;
import com.newrelic.api.agent.weaver.Weaver;

@Weave
public abstract class ErrorHandler {
    @NewField
    private static final String EXCEPTION_ATTRIBUTE_NAME = "javax.servlet.error.exception";

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) {
        final Throwable throwable = (Throwable) request.getAttribute(EXCEPTION_ATTRIBUTE_NAME);

        // call the original implementation
        Weaver.callOriginal();

        if (throwable != null) {
            AgentBridge.privateApi.reportException(throwable);
        }
    }

}
