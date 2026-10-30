package com.moveinsync.rostersync.messaging;

import com.fasterxml.jackson.core.sym.CharsToNameCanonicalizer;

public interface EventPublisher {
    void publish(ChangeEvent event);

    interface Subscriber{
        boolean support(ChangeEvent.Type type);
        void handle(ChangeEvent event);
    }

}
