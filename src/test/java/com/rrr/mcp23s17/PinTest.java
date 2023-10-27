package com.rrr.mcp23s17;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class PinTest {

    static MCP23S17.Pin[] allPins = new MCP23S17.Pin[16];

    @BeforeAll
    static void enumeratePins() {
        for (int i = 0; i < allPins.length; i++) {
            allPins[i] = MCP23S17.Pin.fromPinNumber(i);
        }
    }

    public static Stream<Arguments> sourceProvideAllPins() {
        return Stream.iterate(0, i -> ++i)
                .takeWhile(i -> i < allPins.length)
                .map(i -> Arguments.of(i, allPins[i]));
    }

    public static Stream<Arguments> sourceBoundaryTestFromPinNumber() {
        final int arbitraryStart = -5;
        final int arbitraryEnd = 21;
        final int lowerBoundary = 0;
        final int upperBoundary = 15;
        return Stream.iterate(arbitraryStart, i -> i + 1)
                .takeWhile(i -> i < arbitraryEnd)
                .map(i -> Arguments.of(i, i < lowerBoundary || i > upperBoundary));
    }

    @ParameterizedTest
    @DisplayName("Pin enum returns pinNumber")
    @MethodSource("sourceProvideAllPins")
    void testGetPinNumber(int num, MCP23S17.Pin pin) {
        assertEquals(num, pin.getPinNumber());
    }

    @ParameterizedTest
    @DisplayName("Pin enum returns correct port")
    @MethodSource("sourceProvideAllPins")
    void testIsPortB(int num, MCP23S17.Pin pin) {
        assertEquals(num > 7, pin.isPortB());
    }

    @ParameterizedTest
    @DisplayName("boundary test pin.fromPinNumber() throws")
    @MethodSource("sourceBoundaryTestFromPinNumber")
    void boundaryTestFromPinNumber(int num, boolean bThrows) {
        if (bThrows) {
            assertThrows(IllegalArgumentException.class, () -> MCP23S17.Pin.fromPinNumber(num));
        } else {
            assertDoesNotThrow(() -> MCP23S17.Pin.fromPinNumber(num));
        }
    }

}
