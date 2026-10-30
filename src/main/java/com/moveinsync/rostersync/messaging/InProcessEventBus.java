package com.moveinsync.rostersync.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component

public class InProcessEventBus implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);
    private final List<Subscriber> subscribers;

    public InProcessEventBus(List<Subscriber> subscribers) {
        this.subscribers = subscribers;
    }

    @Override
    public void publish(ChangeEvent event){
        for(Subscriber s : subscribers){
            if(s.supports(event.type())){
                s.handle(event);
            }
        }

        log.info("change event published eventId={} type={} employeeId={} version={}",
                event.eventId(), event.type(), event.employeeId(), event.hrVersion());

    }



}
