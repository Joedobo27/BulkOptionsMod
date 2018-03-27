package com.joedobo27.bom;

import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.questions.Question;
import org.gotti.wurmunlimited.modsupport.bml.BmlBuilder;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;

import java.util.Properties;
import java.util.WeakHashMap;

import static org.gotti.wurmunlimited.modsupport.bml.BmlBuilder.*;


public class ConfigureBinQuestion implements ModQuestion {

    private final Item bin;
    private Question question;
    private final Action action;
    private static WeakHashMap<Action, ConfigureBinQuestion> questions = new WeakHashMap<>();

    ConfigureBinQuestion(Item bin, Action action) {
        this.bin = bin;
        this.question = null;
        this.action = action;
        questions.put(action, this);
    }

   static ConfigureBinQuestion getConfigureBinQuestion(Action action) {
        if (!questions.containsKey(action))
            return null;
        return questions.get(action);
    }

    synchronized void setQuestion(Question question) {
        this.question = question;
    }

    public Question getQuestion() {
        return question;
    }

    @Override
    public void answer(Question question, Properties answer) {
        if (this.question != null && this.question.getId() == question.getId()) {
            int qualityDivision = Integer.parseInt(answer.getProperty("qualityDivision"));
            this.bin.setData2(qualityDivision);
        }
    }

    @Override
    public void sendQuestion(Question question) {
        int qualityDivision = this.bin.getData2() <= 0 ? 100 : this.bin.getData2();
        BmlBuilder bmlBuilder =
            BmlBuilder.builder()
            .withNode(table(2).withAttribute("rows", 1)
                .withNode(label("Divide qualities into groups of x quality ranges."))
                .withNode(input("qualityDivision").withAttribute("maxchars", 20)
                        .withAttribute("text", Integer.toString(qualityDivision))))
            .withNode(button("submit", "Send"));
        bmlBuilder = bmlBuilder.wrapAsDialog(question.getTitle(), false, false, false);
        String bml = bmlBuilder.buildBml();
        question.getResponder().getCommunicator().sendBml(300, 150, true, true,
                                                          bml, 200, 200, 200, question.getTitle());
    }
}
