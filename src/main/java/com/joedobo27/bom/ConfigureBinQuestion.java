package com.joedobo27.bom;

import com.wurmonline.server.items.Item;
import com.wurmonline.server.questions.Question;
import org.gotti.wurmunlimited.modsupport.bml.BmlBuilder;
import org.gotti.wurmunlimited.modsupport.bml.BmlNodeBuilder;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;

import java.util.Properties;

import static org.gotti.wurmunlimited.modsupport.bml.BmlBuilder.*;


public class ConfigureBinQuestion implements ModQuestion {

    private final Item bin;

    ConfigureBinQuestion(Item bin) {
        this.bin = bin;
    }

    @Override
    public void answer(Question question, Properties answer) {
        int qualityDivision = Integer.parseInt(answer.getProperty("qualityDivision"));
        this.bin.setData2(qualityDivision);
    }

    @Override
    public void sendQuestion(Question question) {
        int qualityDivision = this.bin.getData2() <= 0 ? 100 : this.bin.getData2();
        BmlNodeBuilder tableNode = table(2).withAttribute("rows", 1)
                .withNode(label("Divide qualities into groups of x quality ranges."))
                .withNode(input("qualityDivision").withAttribute("maxchars", 20)
                        .withAttribute("text", Integer.toString(qualityDivision))
                        .withAttribute("size", "30,20"));

        BmlBuilder bmlBuilder = BmlBuilder.builder();
        bmlBuilder.withNode(passthough("id", Integer.toString(question.getId())));
        bmlBuilder.withNode(tableNode);
        bmlBuilder.withNode(button("submit", "Send").withAttribute("size", "30,20"));
        bmlBuilder = bmlBuilder.wrapAsDialog(question.getTitle(), false, false, false);
        String bml = bmlBuilder.buildBml();
        question.getResponder().getCommunicator().sendBml(300, 150, true, true,
                                                          bml, 200, 200, 200, question.getTitle());
    }
}
