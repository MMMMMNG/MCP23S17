package com.rrr.mcp23s17;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInputConfig;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.spi.*;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalInput;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalInputProvider;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalOutputProvider;
import com.pi4j.plugin.mock.provider.spi.MockSpiProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.rrr.mcp23s17.MCP23S17.SPI_SPEED_HZ;

public class Pi4jSetupBase {
    protected static final Logger LOG = LoggerFactory.getLogger(MCP23S17Test.class);

    protected Context pi4j;
    protected MockDigitalInput interruptA;
    protected MockDigitalInput interruptB;
    
    protected Spi spi;

    @BeforeEach
    void createMockContext() {
        pi4j = Pi4J.newContextBuilder()
                .add(MockSpiProvider.newInstance(),
                        MockDigitalOutputProvider.newInstance(),
                        MockDigitalInputProvider.newInstance())
                .build();
        var DIconfig = DigitalInputConfig.newBuilder(pi4j).address(21);
        interruptA = (MockDigitalInput) pi4j.create(DIconfig.build());
        interruptB = (MockDigitalInput) pi4j.create(DIconfig.address(22).build());
        interruptA.mockState(DigitalState.HIGH);
        interruptB.mockState(DigitalState.HIGH);

        spi = pi4j.create(buildSpiConfig(pi4j, SpiBus.BUS_0, SPI_SPEED_HZ));
    }

    void mockLow(MockDigitalInput mi) {
        mi.mockState(DigitalState.LOW);
        mi.mockState(DigitalState.HIGH);
    }

    @AfterEach
    void shutdownContext() {
        pi4j.shutdown();
    }

    /**
     * Builds a new SPI instance for the MCP23S17 IC
     *
     * @param pi4j Pi4J context
     * @return SPI instance
     */
    private SpiConfig buildSpiConfig(Context pi4j, SpiBus bus, int frequency) {
        return Spi.newConfigBuilder(pi4j)
                .id("MCPSPI")
                .name("GPIO-Circuit")
                .description("SPI-Config for GPIO-Extension Integrated Circuits (MCP23S17)")
                .bus(bus)
                .chipSelect(SpiChipSelect.CS_0)
                .mode(SpiMode.MODE_0)
                .baud(frequency)
                .build();
    }

}
