# Function definition and composition

- `spring.cloud.function.definition` names the function (or functions, separated by `;`) a deployment exports. With
  one function bean it can be absent; with several, the deployed one is the one it names. Read it from
  `application.properties` and cite it with `ref=config:spring.cloud.function.definition`.
- A composition `parse|quote` runs `parse` and then `quote`; look it up in the catalog with the same string
  (`functions.lookup("parse|quote")`) and test it as one flow when the spec describes the composed behaviour.
- Routing (`spring.cloud.function.routing-expression`, `RoutingFunction`) picks a function per message: one scenario per
  route, plus the input no route matches.
- Input conversion: the catalog converts JSON (`String`, `byte[]`, `Message<byte[]>`) to the function's input type.
  A malformed input is a scenario of its own (what the caller gets back).
