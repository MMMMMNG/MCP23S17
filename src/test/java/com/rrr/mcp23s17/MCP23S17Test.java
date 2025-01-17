package com.rrr.mcp23s17;

import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalInputConfig;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.plugin.mock.provider.gpio.digital.MockDigitalInput;
import com.pi4j.plugin.mock.provider.spi.MockSpi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MCP23S17Test extends Pi4jSetupBase {

    static Stream<Arguments> sourceMultipleNewOnSameBus() {
        return Stream.iterate(0, i -> i + 1).takeWhile(i -> i < 128).map(i -> Arguments.of(i));
    }

    static Stream<Arguments> sourceMultipleNewOnSameBusWithTiedInterrupts() {
        return sourceMultipleNewOnSameBus();
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

    @Test
    void testIODIRwriting() {
        //given
        var cut = MCP23S17.newWithoutInterrupts(spi);
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
        var cut = MCP23S17.newWithoutInterrupts(spi);
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
        var cut = MCP23S17.newWithTiedInterrupts(spi, interruptA);
        var mockSpi = (MockSpi) cut.getSpi();
        var pins = setAllPinsToInput(cut);
        var mockListeners = putMockListenersOnAllPins(pins);
        var pinsToInterrupt = MCPData.builder()
                .read().toINTFA().response(0, 0, 0, 1, 0, 0, 0, 0)
                .next().toINTCAPA()
                .next().toINTFB()
                .next().toINTCAPB().build();
        var setupBuffer = mockSpi.readEntireMockBuffer();
        //when
        mockSpi.write(pinsToInterrupt);
        mockLow(interruptA);
        var readData = mockSpi.readEntireMockBuffer();
        //then
        assertArrayEquals(MCPData.builder()
                .read().toINTFA()
                .next().toINTCAPA()
                .next().toINTFB()
                .next().toINTCAPB().build(), readData);
        assertArrayEquals(MCPData.builder().write().toIOCON().writeData(0, 1, 0, 0, 0, 0, 0, 0).build(), setupBuffer);
        verify(mockListeners.remove(4)).onInterrupt(true, MCP23S17.Pin.PIN4);
        verify(mockListeners.remove(11)).onInterrupt(true, MCP23S17.Pin.PIN12);
        for (var lstnr : mockListeners) {
            verify(lstnr, never()).onInterrupt(anyBoolean(), any());
        }
    }

    private List<MCP23S17.InterruptListener> putMockListenersOnAllPins(List<MCP23S17.PinView> pins) {
        var listeners = new ArrayList<MCP23S17.InterruptListener>();
        for (var pin : pins) {
            var lstnr = mock(MCP23S17.InterruptListener.class);
            pin.addListener(lstnr);
            listeners.add(lstnr);
        }
        return listeners;
    }

    @Test
    void newWithInterrupts() {
        //given
        var cut = MCP23S17.newWithInterrupts(spi, interruptA, interruptB);
        var mockSpi = (MockSpi) cut.getSpi();
        var pins = setAllPinsToInput(cut);
        var mockListeners = putMockListenersOnAllPins(pins);
        var pinsToInterrupt = MCPData.builder()
                .read().toINTFA().response(0, 0, 0, 1, 0, 0, 0, 0)
                .next().toINTCAPA()
                .next().toINTFB()
                .next().toINTCAPB().build();
        var setupBuffer = mockSpi.readEntireMockBuffer();
        //when
        mockSpi.write(pinsToInterrupt);
        mockLow(interruptA);
        mockLow(interruptB);
        var readData = mockSpi.readEntireMockBuffer();
        //then
        assertArrayEquals(MCPData.builder()
                .read().toINTFA()
                .next().toINTCAPA()
                .next().toINTFB()
                .next().toINTCAPB().build(), readData);
        assertArrayEquals(new byte[0], setupBuffer);
        verify(mockListeners.remove(4)).onInterrupt(true, MCP23S17.Pin.PIN4);
        verify(mockListeners.remove(11)).onInterrupt(true, MCP23S17.Pin.PIN12);
        for (var lstnr : mockListeners) {
            verify(lstnr, never()).onInterrupt(anyBoolean(), any());
        }
    }

    @Test
    void newWithPortAInterrupts() {
        //given
        var cut = MCP23S17.newWithPortAInterrupts(spi, interruptA);
        var mockSpi = (MockSpi) cut.getSpi();
        var pins = setAllPinsToInput(cut);
        var mockListeners = putMockListenersOnAllPins(pins);
        var pinsToInterrupt = MCPData.builder()
                .read().toINTFA().response(0, 0, 0, 1, 0, 0, 0, 0)
                .next().toINTCAPA().build();
        var setupBuffer = mockSpi.readEntireMockBuffer();
        //when
        mockSpi.write(pinsToInterrupt);
        mockLow(interruptA);
        var readData = mockSpi.readEntireMockBuffer();
        //then
        assertArrayEquals(MCPData.builder()
                .read().toINTFA()
                .next().toINTCAPA().build(), readData);
        assertArrayEquals(new byte[0], setupBuffer);
        verify(mockListeners.remove(4)).onInterrupt(true, MCP23S17.Pin.PIN4);
        for (var lstnr : mockListeners) {
            verify(lstnr, never()).onInterrupt(anyBoolean(), any());
        }
    }

    @Test
    void newWithPortBInterrupts() {
        //given
        var cut = MCP23S17.newWithPortBInterrupts(spi, interruptB);
        var mockSpi = (MockSpi) cut.getSpi();
        var pins = setAllPinsToInput(cut);
        var mockListeners = putMockListenersOnAllPins(pins);
        var pinsToInterrupt = MCPData.builder()
                .read().toINTFB().response(0, 0, 0, 1, 0, 0, 0, 0)
                .next().toINTCAPB().build();
        var setupBuffer = mockSpi.readEntireMockBuffer();
        //when
        mockSpi.write(pinsToInterrupt);
        mockLow(interruptA);
        mockLow(interruptB);
        var readData = mockSpi.readEntireMockBuffer();
        //then
        assertArrayEquals(MCPData.builder()
                .read().toINTFB()
                .next().toINTCAPB().build(), readData);
        assertArrayEquals(new byte[0], setupBuffer);
        verify(mockListeners.remove(12)).onInterrupt(true, MCP23S17.Pin.PIN12);
        for (var lstnr : mockListeners) {
            verify(lstnr, never()).onInterrupt(anyBoolean(), any());
        }
    }

    @Test
    void getAllPinsAsPulledUpInterruptInput() throws IOException {
        //given
        var cut = MCP23S17.newWithoutInterrupts(spi);
        var mockSpi = (MockSpi) cut.getSpi();
        var expectedPinSetupData = MCPData.builder()
                .write().toIODIRA().writeData(1, 1, 1, 1, 1, 1, 1, 1)
                .next().toIODIRB()
                .next().toGPPUA()
                .next().toGPPUB()
                .next().toGPINTENA()
                .next().toGPINTENB()
                .next().read().toGPIOA()
                .next().toGPIOB().build();
        mockSpi.write(new byte[6]); //to be consumed by setup
        //when
        var pins = cut.getAllPinsAsPulledUpInterruptInput();
        var actualPinSetupData = mockSpi.readEntireMockBuffer();
        //then
        assertArrayEquals(expectedPinSetupData, actualPinSetupData);
        for (var pin : pins) {
            assertTrue(pin.isInput());
            assertTrue(pin.isPulledUp());
            assertTrue(pin.isInterruptEnabled());
        }
    }

    @ParameterizedTest
    @DisplayName("test MCP23S17.multipleNewOnSameBus() factory method including 'mock-turning-on' every pin")
    @MethodSource("sourceMultipleNewOnSameBus")
    void multipleNewOnSameBus(int pinIndex) throws IOException {
        //given
        final int amount = 8;
        var cuts = MCP23S17.multipleNewOnSameBus(spi, amount);
        var mockSpi = (MockSpi) cuts.get(0).getSpi();
        var expectedSetupBuffer = MCPData.builder().write().toIOCON().writeData(0, 0, 0, 0, 1, 0, 0, 0).build();
        var setupBuffer = mockSpi.readEntireMockBuffer();

        var pinsToTurnOn = MCPData.builder()
                .read().toGPIOA()
                .next().toGPIOB()
                .repeatPrevious(8, MCPData::address);
        int chipIndex = pinIndex / 16;
        boolean isPortA = (pinIndex % 16) < 8;
        pinsToTurnOn.prevAtIndex(chipIndex * 2 + (isPortA ? 0 : 1)).respSetBit(pinIndex % 8);

        var pins = new ArrayList<MCP23S17.PinView>();
        for (var chip : cuts) {
            pins.addAll(setAllPinsToInput(chip));
        }

        //confirm all the pins are off before we proceed
        for (var pin : pins) {
            assertFalse(pin.get());
        }
        mockSpi.write(pinsToTurnOn.build()); //prepare mock data
        //when
        for (var chip : cuts) {
            chip.readGPIOA();
            chip.readGPIOB();
        }
        //then
        assertArrayEquals(expectedSetupBuffer, setupBuffer);
        assertTrue(pins.remove(pinIndex).get());
        for (var pin : pins) {
            assertFalse(pin.get());
        }
    }

    @ParameterizedTest
    @MethodSource("sourceMultipleNewOnSameBusWithTiedInterrupts")
    void multipleNewOnSameBusWithTiedInterrupts(int pinIndex) throws IOException {
        //given
        final int amount = 8;
        var mockInterrupts = getMockInterruptPins(8);
        var cuts = MCP23S17.multipleNewOnSameBusWithTiedInterrupts(spi, mockInterrupts.toArray(new DigitalInput[0]), amount, false);
        var mockSpi = (MockSpi) cuts.get(0).getSpi();
        var expectedSetupBuffer = MCPData.builder()
                .write().toIOCON().writeData(0, 1, 0, 0, 1, 0, 0, 0)
                .next().address(amount - 1).build();
        var setupBuffer = mockSpi.readEntireMockBuffer();

        var pinsToInterrupt = MCPData.builder()
                .read().toINTFA()
                .next().toINTCAPA()
                .next().toINTFB()
                .next().toINTCAPB()
                .repeatPrevious(8, MCPData::address);

        int chipIndex = pinIndex / 16;
        boolean isPortA = (pinIndex % 16) < 8;
        pinsToInterrupt.prevAtIndex(chipIndex * 4 + (isPortA ? 0 : 2)).respSetBit(pinIndex % 8);
        pinsToInterrupt.prevAtIndex(chipIndex * 4 + (isPortA ? 1 : 3)).respSetBit(pinIndex % 8);

        var pins = new ArrayList<MCP23S17.PinView>();
        for (var chip : cuts) {
            pins.addAll(setAllPinsToInput(chip));
        }
        var mockListeners = putMockListenersOnAllPins(pins);

        mockSpi.write(pinsToInterrupt.build()); //prepare mock data
        //when
        for (var intr : mockInterrupts) {
            mockLow(intr);
        }
        //then
        assertArrayEquals(expectedSetupBuffer, setupBuffer);
        verify(mockListeners.remove(pinIndex)).onInterrupt(true, pins.get(pinIndex).getPin());
        for (var lstnr : mockListeners) {
            verify(lstnr, never()).onInterrupt(anyBoolean(), any());
        }
    }

    @Test
    @DisplayName("global event listener gets called")
    void testGlobalEventListener() {
        //given
        final MCP23S17.Pin pin = MCP23S17.Pin.PIN5;
        var cut = MCP23S17.newWithTiedInterrupts(spi, interruptA);
        var mockSpi = (MockSpi) cut.getSpi();
        var mockListener = mock(MCP23S17.InterruptListener.class);
        cut.addGlobalListener(mockListener);
        mockSpi.readEntireMockBuffer();
        var data = MCPData.builder()
                .read().toINTFA().respSetBit(pin.getPinNumber())
                .next().toINTCAPA().build();
        mockSpi.write(data);
        //when
        mockLow(interruptA);
        //then
        verify(mockListener).onInterrupt(true, pin);
    }

    @Test
    @DisplayName("global event listener gets called but not after it is removed")
    void testGlobalEventListenerRemove() {
        //given
        final MCP23S17.Pin pin = MCP23S17.Pin.PIN5;
        var cut = MCP23S17.newWithTiedInterrupts(spi, interruptA);
        var mockSpi = (MockSpi) cut.getSpi();
        var mockListener = mock(MCP23S17.InterruptListener.class);
        cut.addGlobalListener(mockListener);
        mockSpi.readEntireMockBuffer();
        var data = MCPData.builder()
                .read().toINTFA().respSetBit(pin.getPinNumber())
                .next().toINTCAPA().build();
        mockSpi.write(data);
        mockLow(interruptA);
        verify(mockListener).onInterrupt(true, pin);
        //when
        cut.removeGlobalListener(mockListener);
        mockSpi.readEntireMockBuffer();
        mockSpi.write(data);
        mockLow(interruptA);
        //then
        verify(mockListener, times(1)).onInterrupt(anyBoolean(), any());
    }

    @Test
    @DisplayName("global event listener throws when null is supplied called")
    void addGlobalListenerThrows() {
        //given
        var cut = MCP23S17.newWithoutInterrupts(spi);
        //when then
        assertThrows(NullPointerException.class, () -> cut.addGlobalListener(null));

    }

    @Test
    @DisplayName("global event listener throws when listener is added twice")
    void addGlobalListenerThrowsWhenSameListenerAddedTwice() {
        //given
        var cut = MCP23S17.newWithoutInterrupts(spi);
        var mockListener = mock(MCP23S17.InterruptListener.class);
        cut.addGlobalListener(mockListener);
        //when - then
        assertThrows(IllegalArgumentException.class, () -> cut.addGlobalListener(mockListener));

    }

    @Test
    void testReadGPINTENA() throws IOException {
        //given
        var readData = MCPData.builder().read().toGPINTENA().response(1, 0, 1, 0, 1, 0, 1, 0).build();
        var cut = MCP23S17.newWithoutInterrupts(spi);
        var mockSpi = (MockSpi) cut.getSpi();
        mockSpi.write(readData);
        //when - then
        assertEquals(readData[2], cut.readGPINTENA());
        readData[2] = 0;
        assertArrayEquals(readData, mockSpi.readEntireMockBuffer());
    }

    @Test
    void testReadGPINTENB() throws IOException {
        //given
        var readData = MCPData.builder().read().toGPINTENB().response(1, 0, 1, 0, 1, 0, 1, 0).build();
        var cut = MCP23S17.newWithoutInterrupts(spi);
        var mockSpi = (MockSpi) cut.getSpi();
        mockSpi.write(readData);
        //when - then
        assertEquals(readData[2], cut.readGPINTENB());
        readData[2] = 0;
        assertArrayEquals(readData, mockSpi.readEntireMockBuffer());

    }

    @Test
    void testReadIOCON() throws IOException {
        //given
        var readData = MCPData.builder().read().toIOCON().response(1, 0, 1, 0, 1, 0, 1, 0).build();
        var cut = MCP23S17.newWithoutInterrupts(spi);
        var mockSpi = (MockSpi) cut.getSpi();
        mockSpi.write(readData);
        //when - then
        assertEquals(readData[2], cut.readIOCON());
        readData[2] = 0;
        assertArrayEquals(readData, mockSpi.readEntireMockBuffer());

    }

    @Test
    @DisplayName("when the readFromGPIOOnInterrupt flag is set, the class ignores any captured interrupts that changed state too briefly")
    void testReadFromGPIOOnInterrupt() throws IOException {
        //given
        final MCP23S17.Pin pin = MCP23S17.Pin.PIN6;
        var cut = MCP23S17.multipleNewOnSameBusWithTiedInterrupts(spi, new DigitalInput[]{interruptA}, 1, true).get(0);
        var mockSpi = (MockSpi) cut.getSpi();
        var pinView = cut.getPinView(pin);
        pinView.setAsInput();
        var mockListener = mock(MCP23S17.InterruptListener.class);
        pinView.addListener(mockListener);
        var preparedData = MCPData.builder().read().respSetBit(pin.getPinNumber())
                .next().next().response(0,0,0,0,0,0,0,0)
                .next().next().next();
        mockSpi.readEntireMockBuffer();
        mockSpi.write(preparedData.build());
        mockLow(interruptA);
        //then
        verify(mockListener, never()).onInterrupt(anyBoolean(), any());

        preparedData.prevAtIndex(2).respSetBit(pin.getPinNumber());
        mockSpi.readEntireMockBuffer();
        mockSpi.write(preparedData.build());
        mockLow(interruptA);
        verify(mockListener, times(1)).onInterrupt(true, pin);
    }

    private List<MockDigitalInput> getMockInterruptPins(int n) {
        var DIConfig = DigitalInputConfig.newBuilder(pi4j).address(0);
        var pins = new ArrayList<MockDigitalInput>();
        for (int j = 0; j < n; j++) {
            var nm = (MockDigitalInput) pi4j.create(DIConfig.address(j).build());
            nm.mockState(DigitalState.HIGH);
            pins.add(nm);
        }
        return pins;
    }
}