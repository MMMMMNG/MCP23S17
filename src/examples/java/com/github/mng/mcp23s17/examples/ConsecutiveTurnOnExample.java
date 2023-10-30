package com.github.mng.mcp23s17.examples;

import com.pi4j.Pi4J;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.io.spi.SpiChipSelect;
import com.pi4j.io.spi.SpiConfigBuilder;
import com.pi4j.plugin.pigpio.provider.spi.PiGpioSpiProvider;
import com.rrr.mcp23s17.MCP23S17;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ConsecutiveTurnOnExample {
    private static final Logger log = LoggerFactory.getLogger(ConsecutiveTurnOnExample.class);
    public static void main(String[] args) {
        log.info("'sup, starting MCP23S17 turn-on example.");
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

        var chips = MCP23S17.multipleNewOnSameBus(spi,2);

        var pins = new ArrayList<MCP23S17.PinView>(32);

        //init all pins to outputs
        for(var chip : chips){
            var iter = chip.getPinViewIterator();
            while(iter.hasNext()){
                var pin = iter.next();
                pin.setAsOutput();
                pins.add(pin);
            }
            chip.writeIODIRA();
            chip.writeIODIRB();
        }
        try {
            mainApp(chips, pins);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            context.shutdown();
        }

    }

    private static void mainApp(List<MCP23S17> chips, ArrayList<MCP23S17.PinView> pins) throws InterruptedException {
        int oldIndex =  0;
        int onIndex = 0;
        //start actual app
        log.info("starting example...");
        while(!Thread.interrupted()){
            if(onIndex == 0 && oldIndex == 31){
                log.info("Went through all 32 pins.");
            }
            //change state
            pins.get(oldIndex).clear();
            pins.get(onIndex).set();
            //write to every possible output register
            chips.get(0).writeOLATA();
            chips.get(0).writeOLATB();
            chips.get(1).writeOLATA();
            chips.get(1).writeOLATB();
            //increase index
            oldIndex = onIndex;
            onIndex = (onIndex + 1) % pins.size();
            Thread.sleep(100);
        }
    }
}
