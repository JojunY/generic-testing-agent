# Spring State Machine

```java
StateMachineTestPlanBuilder.<States, Events>builder()
    .stateMachine(machine)
    .step().expectState(States.CREATED).and()
    .step().sendEvent(Events.PAY).expectStateChanged(1).expectState(States.PAID).and()
    .step().sendEvent(Events.SHIP).expectState(States.SHIPPED).and()
    .build().test();
```

Add one plan per forbidden event (`sendEvent(Events.SHIP)` in `CREATED` → state unchanged) and per guard/action failure.
