package com.github.mng.mcp23s17.examples;

import com.pi4j.Pi4J;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalInputConfigBuilder;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.io.spi.SpiChipSelect;
import com.pi4j.io.spi.SpiConfigBuilder;
import com.pi4j.plugin.pigpio.provider.gpio.digital.PiGpioDigitalInputProvider;
import com.pi4j.plugin.pigpio.provider.spi.PiGpioSpiProvider;
import com.pi4j.util.Console;
import com.rrr.mcp23s17.MCP23S17;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;

public class InputInterruptExample {
    private static final Logger log = LoggerFactory.getLogger(InputInterruptExample.class);
    public static void main(String[] args) throws IOException {
        log.info("'sup, starting MCP23S17 interrupt example.");
        log.info("whoohoo 🔥");

        var context = Pi4J.newAutoContext();

        var spi = context.create(SpiConfigBuilder.newInstance(context)
                .id("MCP23S17 example")
                .bus(SpiBus.BUS_0)
                .baud(1_000_000)
                .channel(0)
                .chipSelect(SpiChipSelect.CS_0)
                .provider(PiGpioSpiProvider.class)
                .build()
        );

        var intChip0 = context.create(DigitalInputConfigBuilder.newInstance(context)
                .id("MCP23S17 chip0 irupt")
                .address(22)
                .provider(PiGpioDigitalInputProvider.class)
                .build()
        );

        var intChip1 = context.create(DigitalInputConfigBuilder.newInstance(context)
                .id("MCP23S17 chip1 irupt")
                .address(23)
                .provider(PiGpioDigitalInputProvider.class)
                .build()
        );

        var chips = MCP23S17.multipleNewOnSameBusWithTiedInterrupts(spi,new DigitalInput[]{intChip0, intChip1},2,true);

        var pins = new ArrayList<MCP23S17.PinView>(32);
        pins.addAll(chips.get(0).getAllPinsAsPulledUpInterruptInput());
        pins.addAll(chips.get(1).getAllPinsAsPulledUpInterruptInput());

        for (int i = 0; i < pins.size(); i++) {
            int finalI = i;
            pins.get(i).addListener((m, p) -> log.info("detected statechange to {} on pin {}", m, finalI));
        }

        var cons = new Console();
        cons.box("wait for exit");
        try {
            cons.waitForExit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            context.shutdown();
        }
    }
}
