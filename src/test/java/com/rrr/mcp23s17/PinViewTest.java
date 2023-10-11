package com.rrr.mcp23s17;

import com.pi4j.plugin.mock.provider.spi.MockSpi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

public class PinViewTest extends Pi4jSetupBase {

    private List<MCP23S17.PinView> cuts = new ArrayList<>();
    private MCP23S17 chip;
    private MockSpi mockSpi;

    static Arguments funcToArg(Function<MCP23S17.PinView, Boolean> checker,
                               Consumer<MCP23S17.PinView> setter,
                               Consumer<MCP23S17.PinView> resetter,
                               Consumer<MCP23S17> writeA,
                               Consumer<MCP23S17> writeB,
                               UnaryOperator<MCPData> dataPortA,
                               UnaryOperator<MCPData> dataPortB,
                               boolean dataInverted) {
        return Arguments.of(checker, setter, resetter, writeA, writeB, dataPortA, dataPortB, dataInverted);
    }

    static Stream<Arguments> sourceStandardMethods() {
        return Stream.of(
                funcToArg(pv -> {
                            pv.setAsOutput();
                            boolean val = pv.get();
                            pv.setAsInput();
                            return val;
                        },
                        MCP23S17.PinView::set, MCP23S17.PinView::clear,
                        MCP23S17::writeOLATA, MCP23S17::writeOLATB,
                        MCPData::toOLATA, MCPData::toOLATB, false),
                funcToArg(MCP23S17.PinView::isInputInverted,
                        MCP23S17.PinView::invertInput, MCP23S17.PinView::uninvertInput,
                        MCP23S17::writeIPOLA, MCP23S17::writeIPOLB,
                        MCPData::toIPOLA, MCPData::toIPOLB, false),
                funcToArg(MCP23S17.PinView::isOutput,
                        MCP23S17.PinView::setAsOutput, MCP23S17.PinView::setAsInput,
                        MCP23S17::writeIODIRA, MCP23S17::writeIODIRB,
                        MCPData::toIODIRA, MCPData::toIODIRB, true),
                funcToArg(MCP23S17.PinView::isInterruptEnabled,
                        MCP23S17.PinView::enableInterrupt, MCP23S17.PinView::disableInterrupt,
                        MCP23S17::writeGPINTENA, MCP23S17::writeGPINTENB,
                        MCPData::toGPINTENA, MCPData::toGPINTENB, false),
                funcToArg(MCP23S17.PinView::getDefaultComparisonValue,
                        pv -> pv.setDefaultComparisonValue(true), pv -> pv.setDefaultComparisonValue(false),
                        MCP23S17::writeDEFVALA, MCP23S17::writeDEFVALB,
                        MCPData::toDEFVALA, MCPData::toDEFVALB, false),
                funcToArg(MCP23S17.PinView::isInterruptChangeMode,
                        MCP23S17.PinView::toInterruptChangeMode, MCP23S17.PinView::toInterruptComparisonMode,
                        MCP23S17::writeINTCONA, MCP23S17::writeINTCONB,
                        MCPData::toINTCONA, MCPData::toINTCONB, true),
                funcToArg(MCP23S17.PinView::isPulledUp,
                        MCP23S17.PinView::enablePullUp, MCP23S17.PinView::disablePullUp,
                        MCP23S17::writeGPPUA, MCP23S17::writeGPPUB,
                        MCPData::toGPPUA, MCPData::toGPPUB, false)
        );
    }

    @ParameterizedTest
    @DisplayName("Test the standard PinView-config kind of methods")
    @MethodSource("sourceStandardMethods")
    void standardMethods(Function<MCP23S17.PinView, Boolean> checker,
                         Consumer<MCP23S17.PinView> setter,
                         Consumer<MCP23S17.PinView> resetter,
                         Consumer<MCP23S17> writeA,
                         Consumer<MCP23S17> writeB,
                         UnaryOperator<MCPData> dataPortA,
                         UnaryOperator<MCPData> dataPortB,
                         boolean dataInverted) {
        //given
        var port1 = dataPortA.apply(MCPData.builder().write());
        if (!dataInverted) {
            port1.writeData(1, 1, 1, 1, 1, 1, 1, 1);
        }
        var expInvert = dataPortB.apply(port1.next()).build();
        port1 = dataPortA.apply(MCPData.builder().write());
        if (dataInverted) {
            port1.writeData(1, 1, 1, 1, 1, 1, 1, 1);
        }
        var expUninvert = dataPortB.apply(port1.next()).build();
        //when - then
        for (var cut : cuts) {
            setter.accept(cut);
            assertTrue(checker.apply(cut));
        }
        writeA.accept(chip);
        writeB.accept(chip);
        assertArrayEquals(expInvert, mockSpi.readEntireMockBuffer());
        for (var cut : cuts) {
            resetter.accept(cut);
            assertFalse(checker.apply(cut));
        }
        writeA.accept(chip);
        writeB.accept(chip);
        assertArrayEquals(expUninvert, mockSpi.readEntireMockBuffer());
    }

    @Test
    void testGetFromRead() throws IOException {
        //given
        final MCP23S17.Pin pin = MCP23S17.Pin.PIN6;
        var preparedData = MCPData.builder()
                .read().respSetBit(pin.getPinNumber()).next().read().build();
        var expectedData = MCPData.builder()
                .read().toGPIOA()
                .next().toGPIOB().build();
        mockSpi.write(preparedData);
        //when - then
        assertTrue(cuts.get(pin.getPinNumber()).getFromRead());
        assertArrayEquals(mockSpi.readEntireMockBuffer(), expectedData);
    }

    @Test
    void testRemoveListener() {
        //given
        final MCP23S17.Pin pin = MCP23S17.Pin.PIN4;
        var interruptChip = MCP23S17.newWithTiedInterrupts(pi4j, spi, interruptA);
        var cut = interruptChip.getPinView(pin);
        var mockSpi = (MockSpi) interruptChip.getSpi();
        var mockListener = mock(MCP23S17.InterruptListener.class);
        cut.addListener(mockListener);
        mockSpi.readEntireMockBuffer();
        var data = MCPData.builder()
                .read().toINTFA().respSetBit(pin.getPinNumber())
                .next().toINTCAPA().build();
        mockSpi.write(data);
        mockLow(interruptA);
        verify(mockListener).onInterrupt(true, pin);
        //when
        cut.removeListener(mockListener);

        mockSpi.readEntireMockBuffer();
        mockSpi.write(data);
        mockLow(interruptA);
        //then
        verify(mockListener, times(1)).onInterrupt(anyBoolean(), any());
    }

    @BeforeEach
    void setupChip() {
        chip = MCP23S17.newWithoutInterrupts(pi4j, spi);
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
}
