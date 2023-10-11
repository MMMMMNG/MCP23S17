package com.rrr.mcp23s17;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInputConfig;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalOutputBuilder;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalInput;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalInputProvider;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalOutputProvider;
import com.pi4j.plugin.mock.provider.spi.MockSpiProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Pi4jSetupBase {
    protected static final Logger LOG = LoggerFactory.getLogger(MCP23S17Test.class);

    protected Context pi4j;
    protected DigitalOutput chipSelect;
    protected MockDigitalInput interruptA;
    protected MockDigitalInput interruptB;

    @BeforeEach
    void createMockContext() {
        pi4j = Pi4J.newContextBuilder()
                .add(MockSpiProvider.newInstance(),
                        MockDigitalOutputProvider.newInstance(),
                        MockDigitalInputProvider.newInstance())
                .build();
        chipSelect = DigitalOutputBuilder.newInstance(pi4j).address(20).build();
        var DIconfig = DigitalInputConfig.newBuilder(pi4j).address(21);
        interruptA = (MockDigitalInput) pi4j.create(DIconfig.build());
        interruptB = (MockDigitalInput) pi4j.create(DIconfig.address(22).build());
        interruptA.mockState(DigitalState.HIGH);
        interruptB.mockState(DigitalState.HIGH);

    }

    void mockLow(MockDigitalInput mi) {
        mi.mockState(DigitalState.LOW);
        mi.mockState(DigitalState.HIGH);
    }

    @AfterEach
    void shutdownContext() {
        pi4j.shutdown();
    }

}
