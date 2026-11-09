package com.nagad.npsb.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Work that runs after the response has been sent (FastAPI's BackgroundTasks), e.g. sending emails an admin approved.
 * The tasks run on Spring's task executor; each one opens its own transactions (it runs outside the request).
 */
@Component
public class BackgroundTasks {
    static final String ATTRIBUTE = "npsb.backgroundTasks";

    private final TaskExecutor executor;

    public BackgroundTasks(@Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.executor = executor;
    }

    /** Run the task once the current response has been sent (outside a request: right away, in the background). */
    @SuppressWarnings("unchecked")
    public void add(Runnable task) {
        HttpServletRequest request = ClientInfo.currentRequest();
        if (request == null) {
            executor.execute(task);
            return;
        }
        List<Runnable> tasks = (List<Runnable>) request.getAttribute(ATTRIBUTE);
        if (tasks == null) {
            tasks = new ArrayList<>();
            request.setAttribute(ATTRIBUTE, tasks);
        }
        tasks.add(task);
    }

    /** Called by RequestLoggingFilter after the response was written. */
    @SuppressWarnings("unchecked")
    void startAfterResponse(HttpServletRequest request) {
        List<Runnable> tasks = (List<Runnable>) request.getAttribute(ATTRIBUTE);
        if (tasks == null) return;
        request.removeAttribute(ATTRIBUTE);
        for (Runnable task : tasks) executor.execute(task);
    }
}
