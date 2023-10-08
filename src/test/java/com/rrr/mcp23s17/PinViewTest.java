package com.rrr.mcp23s17;

import com.pi4j.io.spi.SpiBus;
import com.pi4j.plugin.mock.provider.spi.MockSpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PinViewTest extends Pi4jSetupBase {

    private List<MCP23S17.PinView> cuts = new ArrayList<>();
    private MCP23S17 chip;
    private MockSpi mockSpi;

    @BeforeEach
    void setupChip() {
        chip = MCP23S17.newWithoutInterrupts(pi4j, SpiBus.BUS_0, chipSelect);
        var it = chip.getPinViewIterator();
        while (it.hasNext()) {
            var pw = it.next();
            cuts.add(pw);
        }
        mockSpi = (MockSpi) chip.getSpi();
    }

    @AfterEach
    void clearCuts() {
        cuts.clear();
    }

    @Test
    void inversion() {
        //given
        var expInvert = MCPData.builder().write().toIPOLA().writeData(1, 1, 1, 1, 1, 1, 1, 1)
                .next().toIPOLB().build();
        var expUninvert = MCPData.builder().write().toIPOLA()
                .next().toIPOLB().build();
        //when - then
        for (var cut : cuts) {
            cut.invertInput();
            assertTrue(cut.isInputInverted());
        }
        chip.writeIPOLA();
        chip.writeIPOLB();
        assertArrayEquals(expInvert, mockSpi.readEntireMockBuffer());
        for (var cut : cuts) {
            cut.uninvertInput();
            assertFalse(cut.isInputInverted());
        }
        chip.writeIPOLA();
        chip.writeIPOLB();
        assertArrayEquals(expUninvert, mockSpi.readEntireMockBuffer());

    }
}
