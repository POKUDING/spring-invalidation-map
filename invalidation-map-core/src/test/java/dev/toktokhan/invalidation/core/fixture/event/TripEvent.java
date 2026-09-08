package dev.toktokhan.invalidation.core.fixture.event;

import org.springframework.context.ApplicationEvent;

public abstract class TripEvent extends ApplicationEvent {

    protected TripEvent(Object source) {
        super(source);
    }
}
