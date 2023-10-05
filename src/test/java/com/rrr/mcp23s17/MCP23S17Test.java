package com.rrr.mcp23s17;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInputConfig;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalOutputBase;
import com.pi4j.io.gpio.digital.DigitalOutputBuilder;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.plugin.mock.platform.MockPlatform;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalOutputProvider;
import com.pi4j.plugin.mock.provider.spi.MockSpi;
import com.pi4j.plugin.mock.provider.spi.MockSpiProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MCP23S17Test {

    private Context pi4j;
    private DigitalOutput chipSelect;

    @BeforeEach
    void createMockContext() {
        pi4j = Pi4J.newContextBuilder()
                .add(   MockSpiProvider.newInstance(),
                        MockDigitalOutputProvider.newInstance())
                .build();
        chipSelect = DigitalOutputBuilder.newInstance(pi4j).address(20).build();
    }

    @AfterEach
    void shutdownContext() {
        pi4j.shutdown();
    }

    @Test
    void newWithoutInterrupts() {
        //given
        var cut = MCP23S17.newWithoutInterrupts(pi4j, SpiBus.BUS_0, chipSelect);
        var mockSpi = (MockSpi) cut.getSpi();

        //mockSpi.write

    }
}