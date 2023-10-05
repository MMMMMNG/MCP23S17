package com.rrr.mcp23s17;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.*;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalInput;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalInputProvider;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalOutputProvider;
import com.pi4j.plugin.mock.provider.spi.MockSpi;
import com.pi4j.plugin.mock.provider.spi.MockSpiProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MCP23S17Test {

    private static final Logger LOG = LoggerFactory.getLogger(MCP23S17Test.class);

    private Context pi4j;
    private DigitalOutput chipSelect;
    private MockDigitalInput interruptA;
    private MockDigitalInput interruptB;

    @BeforeEach
    void createMockContext() {
        pi4j = Pi4J.newContextBuilder()
                .add(MockSpiProvider.newInstance(),
                        MockDigitalOutputProvider.newInstance(),
                        MockDigitalInputProvider.newInstance())
                .build();
        chipSelect = DigitalOutputBuilder.newInstance(pi4j).address(20).build();
        interruptA = (MockDigitalInput) pi4j.create(DigitalInputConfig.newBuilder(pi4j).address(21).build());
        interruptB = (MockDigitalInput) pi4j.create(DigitalInputConfig.newBuilder(pi4j).address(22).build());
        interruptA.mockState(DigitalState.HIGH);
        interruptB.mockState(DigitalState.HIGH);

    }

    @AfterEach
    void shutdownContext() {
        pi4j.shutdown();
    }

    private List<MCP23S17.PinView> setAllPinsToInput(MCP23S17 chip) {
        var pins = new ArrayList<MCP23S17.PinView>();
        for (var it = chip.getPinViewIterator(); it.hasNext(); ) {
            MCP23S17.PinView pin = it.next();
            pin.setAsInput();
            pins.add(pin);
        }
        return pins;
    }

    private byte[] readBytesFromMockSpi(MockSpi spi, int amount) {
        var bytes = new byte[amount];
        spi.read(bytes, amount);
        return bytes;
    }

    private byte[] getByteArrayOfNegOnes(int amount) {
        var bytes = new byte[amount];
        for (int i = 0; i < amount; i++) {
            bytes[i] = -1;
        }
        return bytes;
    }

    @Test
    void testIODIRwriting() {
        //given
        var cut = MCP23S17.newWithoutInterrupts(pi4j, SpiBus.BUS_0, chipSelect);
        var mockSpi = (MockSpi) cut.getSpi();
        var pins = setAllPinsToInput(cut);
        var expectedData = MCPData.builder()
                .write().toIODIRA().writeData(1, 1, 1, 1, 1, 1, 1, 1) //all inputs
                .next()
                .toIODIRB()
                .build();
        //verify that no data was sent during setup
        assertEquals(-1, mockSpi.read());
        //when
        cut.writeIODIRA();
        cut.writeIODIRB();
        var writtenByCut = mockSpi.readEntireMockBuffer();
        //then
        assertEquals(6, writtenByCut.length);
        assertArrayEquals(expectedData, writtenByCut);
        //verify all the pins are inputs
        for (var pin : pins) {
            assertTrue(pin.isInput());
        }
    }

    @Test
    void newWithoutInterrupts() {
        //given
        var cut = MCP23S17.newWithoutInterrupts(pi4j, SpiBus.BUS_0, chipSelect);
        var mockSpi = (MockSpi) cut.getSpi();
        var pins = setAllPinsToInput(cut);
        var turnEverySecondPinOn = MCPData.builder().read().response(1, 0, 1, 0, 1, 0, 1, 0).next().build();
        //before we pretend every second pin is on verify that all pins are off
        for (var pin : pins) {
            assertFalse(pin.get());
        }
        //when
        mockSpi.write(turnEverySecondPinOn);
        try {
            cut.readGPIOA();
            cut.readGPIOB();
        } catch (Exception e) {
            //don't care
        }
        //then
        for (int i = 0; i < pins.size(); i++) {
            assertEquals(i % 2 == 1, pins.get(i).get());
        }
    }

    @Test
    void newWithTiedInterrupts() {
        //given
        var cut = MCP23S17.newWithTiedInterrupts(pi4j, SpiBus.BUS_0, chipSelect, interruptA);
        var mockSpi = (MockSpi) cut.getSpi();
        var pins = setAllPinsToInput(cut);
        //var mockListeners = putMockListenersOnAllPins(pins);
        var pinsToInterrupt = MCPData.builder().read().toINTCAPA()
                .response(0, 0, 0, 1, 0, 0, 0, 0).next().toINTCAPB().build();
        var setupBuffer = mockSpi.readEntireMockBuffer();
        //when
        mockSpi.write(pinsToInterrupt);
        interruptA.mockState(DigitalState.LOW);
        interruptA.mockState(DigitalState.HIGH);

        //then
        assertArrayEquals(MCPData.builder().write().toIOCON().writeData(0,1,0,0,0,0,0,0).build(),setupBuffer);
    }

    private void putMockListenersOnAllPins(List<MCP23S17.PinView> pins) {

    }

    @Test
    void newWithInterrupts() {
    }

    @Test
    void newWithPortAInterrupts() {
    }

    @Test
    void newWithPortBInterrupts() {
    }
}