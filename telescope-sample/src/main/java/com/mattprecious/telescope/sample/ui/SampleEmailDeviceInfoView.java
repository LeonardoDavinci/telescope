package com.mattprecious.telescope.sample.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import com.mattprecious.telescope.EmailDeviceInfoLens;
import com.mattprecious.telescope.TelescopeLayout;
import com.mattprecious.telescope.sample.R;

import butterknife.ButterKnife;
import butterknife.InjectView;

public class SampleEmailDeviceInfoView extends FrameLayout {
  @InjectView(R.id.telescope)
  TelescopeLayout telescopeView;

  @InjectView(R.id.trigger_manually)
  Button triggerManually;

  public SampleEmailDeviceInfoView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override protected void onFinishInflate() {
    super.onFinishInflate();
    ButterKnife.inject(this);

    telescopeView.setLens(new EmailDeviceInfoLens(getContext(), "Bug report", "bugs@blackhole.io"));
    triggerManually.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        telescopeView.start();
      }
    });
  }
}
