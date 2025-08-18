package io.karatelabs.js;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

class JsNumber extends JsObject {

    final Number value;

    JsNumber() {
        this(0);
    }

    JsNumber(Number value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value.toString();
    }

    @Override
    Prototype initPrototype() {
        Prototype wrapped = super.initPrototype();
        return new Prototype(wrapped) {
            @Override
            public Object getProperty(String propName) {
                switch (propName) {
                    case "toFixed":
                        return (Invokable) args -> {
                            int digits = 0;
                            if (args.length > 0) {
                                digits = Terms.toNumber(args[0]).intValue();
                            }
                            double doubleValue = value.doubleValue();
                            BigDecimal bd = BigDecimal.valueOf(doubleValue);
                            bd = bd.setScale(digits, RoundingMode.HALF_UP);
                            StringBuilder pattern = new StringBuilder("0");
                            if (digits > 0) {
                                pattern.append(".");
                                pattern.append("0".repeat(digits));
                            }
                            DecimalFormat df = new DecimalFormat(pattern.toString());
                            return df.format(bd.doubleValue());
                        };
                    // static ==========================================================================================
                    case "valueOf":
                        return (Invokable) args -> value;
                }
                return null;
            }
        };
    }

    @Override
    public Object invoke(Object... args) {
        Number temp = 0;
        if (args.length > 0) {
            temp = Terms.toNumber(args[0]);
        }
        return new JsNumber(temp);
    }

}
