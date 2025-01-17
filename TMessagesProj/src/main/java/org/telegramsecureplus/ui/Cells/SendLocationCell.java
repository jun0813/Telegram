/*
 * This is the source code of Telegram for Android v. 2.x
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2015.
 */

package org.telegramsecureplus.ui.Cells;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegramsecureplus.android.AndroidUtilities;
import org.telegramsecureplus.android.LocaleController;
import org.telegramsecureplus.messenger.R;
import org.telegramsecureplus.ui.Components.LayoutHelper;
import org.telegramsecureplus.ui.Components.SimpleTextView;

public class SendLocationCell extends FrameLayout {

    private SimpleTextView accurateTextView;
    private SimpleTextView titleTextView;

    public SendLocationCell(Context context) {
        super(context);

        ImageView imageView = new ImageView(context);
        imageView.setImageResource(R.drawable.pin);
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 0 : 17, 13, LocaleController.isRTL ? 17 : 0, 0));

        titleTextView = new SimpleTextView(context);
        titleTextView.setTextSize(16);
        titleTextView.setTextColor(0xff377aae);
        titleTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        titleTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 20, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 16 : 73, 12, LocaleController.isRTL ? 73 : 16, 0));

        accurateTextView = new SimpleTextView(context);
        accurateTextView.setTextSize(14);
        accurateTextView.setTextColor(0xff999999);
        accurateTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        addView(accurateTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 20, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 16 : 73, 37, LocaleController.isRTL ? 73 : 16, 0));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(66), MeasureSpec.EXACTLY));
    }

    public void setText(String title, String text) {
        titleTextView.setText(title);
        accurateTextView.setText(text);
    }
}
