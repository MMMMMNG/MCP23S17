# MCP23S17
Java class for interfacing with the MCP23S17 IO expander with a Raspberry Pi.

## Building
Clone this repository and run

    ./gradlew jar
    
to build a JAR containing the MCP23S17 class only,

    ./gradlew shadowJar
    
to build a shadow JAR containing the MCP23S17 class *and* the
[pi4j](https://pi4j.com) dependency,

    ./gradlew sourcesJar
    
to build a sources JAR, and/or

    ./gradlew javadocJar
    
to build javadoc JAR.
