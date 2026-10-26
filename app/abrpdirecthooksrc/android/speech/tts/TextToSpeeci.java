package android.speech.tts;

import android.content.Context;
import com.iternio.abrpapp.aivi.CerenceTtsBase;

/** ABRP-facing BYD Cerence facade exposing the voices installed in the vehicle. */
public final class TextToSpeeci extends CerenceTtsBase {
    public TextToSpeeci(Context context, TextToSpeech.OnInitListener listener) {
        super(context, listener);
    }

    public TextToSpeeci(Context context, TextToSpeech.OnInitListener listener, String enginePackage) {
        super(context, listener, enginePackage);
    }
}
