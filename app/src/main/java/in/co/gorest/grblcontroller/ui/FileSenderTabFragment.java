/*
 *  /**
 *  * Copyright (C) 2017  Grbl Controller Contributors
 *  *
 *  * This program is free software; you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation; either version 2 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License along
 *  * with this program; if not, write to the Free Software Foundation, Inc.,
 *  * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *  * <http://www.gnu.org/licenses/>
 *
 */

package in.co.gorest.grblcontroller.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.joanzapata.iconify.widget.IconButton;
import com.joanzapata.iconify.widget.IconTextView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

import droidninja.filepicker.FilePickerBuilder;
import droidninja.filepicker.FilePickerConst;
import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.databinding.FragmentFileSenderTabBinding;
import in.co.gorest.grblcontroller.events.BluetoothDisconnectEvent;
import in.co.gorest.grblcontroller.events.GrblErrorEvent;
import in.co.gorest.grblcontroller.events.UiToastEvent;
import in.co.gorest.grblcontroller.helpers.EnhancedSharedPreferences;
import in.co.gorest.grblcontroller.listners.FileSenderListner;
import in.co.gorest.grblcontroller.listners.MachineStatusListner;
import in.co.gorest.grblcontroller.model.Overrides;
import in.co.gorest.grblcontroller.service.FileStreamerIntentService;
import in.co.gorest.grblcontroller.util.GrblUtils;

public class FileSenderTabFragment extends BaseFragment implements View.OnClickListener{

    private static final String TAG = FileSenderTabFragment.class.getSimpleName();
    private MachineStatusListner machineStatus;
    private FileSenderListner fileSender;
    private EnhancedSharedPreferences sharedPref;

    public FileSenderTabFragment() {}

    public static FileSenderTabFragment newInstance() {
        return new FileSenderTabFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        machineStatus = MachineStatusListner.getInstance();
        fileSender = FileSenderListner.getInstance();
        sharedPref = EnhancedSharedPreferences.getInstance(getActivity().getApplicationContext(), getString(R.string.shared_preference_key));
    }

    @Override
    public void onStart(){
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop(){
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        FragmentFileSenderTabBinding binding = DataBindingUtil.inflate(inflater, R.layout.fragment_file_sender_tab, container, false);
        binding.setMachineStatus(machineStatus);
        binding.setFileSender(fileSender);
        View view = binding.getRoot();

        IconTextView selectGcodeFile = view.findViewById(R.id.select_gcode_file);
        selectGcodeFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(hasExternalStorageReadPermission()){
                    getFilePicker();
                }else{
                    askExternalReadPermission();
                }
            }
        });

        final IconButton enableChecking = view.findViewById(R.id.enable_checking);
        enableChecking.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(machineStatus.getState().equals(MachineStatusListner.STATE_IDLE) || machineStatus.getState().equals(MachineStatusListner.STATE_CHECK)){
                    stopFileStreaming();
                    fragmentInteractionListener.onGcodeCommandReceived(GrblUtils.GRBL_TOGGLE_CHECK_MODE_COMMAND);
                }
            }
        });

        final IconButton startStreaming = view.findViewById(R.id.start_streaming);
        startStreaming.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(fileSender.getGcodeFile() == null){
                    EventBus.getDefault().post(new UiToastEvent(getString(R.string.no_gcode_file_selected)));
                    return;
                }
                startFileStreaming();
            }
        });

        final IconButton stopStreaming = view.findViewById(R.id.stop_streaming);
        stopStreaming.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopFileStreaming();
            }
        });

        for(int resourseId: new Integer[]{R.id.feed_override_fine_minus, R.id.feed_override_fine_plus, R.id.feed_override_coarse_minus, R.id.feed_overrirde_coarse_pluse,
            R.id.spindle_override_fine_minus, R.id.spindle_override_fine_plus, R.id.spindle_override_coarse_minus, R.id.spindle_override_coarse_plus,
            R.id.rapid_overrides_reset, R.id.rapid_override_medium, R.id.rapid_overrirde_low,
            R.id.toggle_spindle, R.id.toggle_flood_coolant, R.id.toggle_mist_coolant }){
            IconButton iconButton = view.findViewById(resourseId);
            iconButton.setOnClickListener(this);
        }


        return view;
    }

    private void startFileStreaming(){

        if(machineStatus.getState().equals(MachineStatusListner.STATE_RUN) && FileStreamerIntentService.getIsServiceRunning()){
            fragmentInteractionListener.onGrblRealTimeCommandReceived(GrblUtils.GRBL_PAUSE_COMMAND);
            return;
        }

        if(machineStatus.getState().equals(MachineStatusListner.STATE_HOLD)){
            fragmentInteractionListener.onGrblRealTimeCommandReceived(GrblUtils.GRBL_RESUME_COMMAND);
            return;
        }

        if(!FileStreamerIntentService.getIsServiceRunning() && (machineStatus.getState().equals(MachineStatusListner.STATE_IDLE) || machineStatus.getState().equals(MachineStatusListner.STATE_CHECK))){

            if(machineStatus.getState().equals(MachineStatusListner.STATE_CHECK)){

                FileStreamerIntentService.setShouldContinue(true);
                Intent intent = new Intent(getActivity().getApplicationContext(), FileStreamerIntentService.class);
                intent.putExtra(FileStreamerIntentService.CHECK_MODE_ENABLED, machineStatus.getState().equals(MachineStatusListner.STATE_CHECK));
                intent.putExtra(FileStreamerIntentService.FASTER_CHECK_MODE_ENABLED, sharedPref.getBoolean(getString(R.string.enable_fast_check_mode), false));
                getActivity().startService(intent);
            }else{
                new AlertDialog.Builder(getActivity())
                        .setTitle("Ready to start file sending")
                        .setMessage("do check every thing is reaady especially spindle status!")
                        .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                FileStreamerIntentService.setShouldContinue(true);
                                Intent intent = new Intent(getActivity().getApplicationContext(), FileStreamerIntentService.class);
                                getActivity().startService(intent);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }

        }
    }

    private void stopFileStreaming(){
        if(FileStreamerIntentService.getIsServiceRunning()){
            FileStreamerIntentService.setShouldContinue(false);
        }

        Intent intent = new Intent(getActivity().getApplicationContext(), FileStreamerIntentService.class);
        getActivity().stopService(intent);

        String stopButtonBehaviour = sharedPref.getString(getString(R.string.streaming_stop_button_behaviour), "0");
        if(stopButtonBehaviour.equals("1") || machineStatus.getState().equals(MachineStatusListner.STATE_HOLD)){
            if(machineStatus.getState().equals(MachineStatusListner.STATE_RUN) || machineStatus.getState().equals(MachineStatusListner.STATE_HOLD)){
                fragmentInteractionListener.onGrblRealTimeCommandReceived(GrblUtils.GRBL_RESET_COMMAND);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode){
            case FilePickerConst.REQUEST_CODE_DOC:
                if(resultCode == Activity.RESULT_OK && data != null){
                    ArrayList<String> pickedFiles = data.getStringArrayListExtra(FilePickerConst.KEY_SELECTED_DOCS);
                    if(pickedFiles.size() > 0){
                        fileSender.setGcodeFile(new File(pickedFiles.get(0)));
                        new ReadFileAsyncTask().execute(fileSender.getGcodeFile());
                    }
                }
                break;
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();

        switch(id) {
            case R.id.feed_override_fine_minus:
                sendRealTimeCommand(Overrides.CMD_FEED_OVR_FINE_MINUS);
                break;

            case R.id.feed_override_fine_plus:
                sendRealTimeCommand(Overrides.CMD_FEED_OVR_FINE_PLUS);
                break;

            case R.id.feed_override_coarse_minus:
                sendRealTimeCommand(Overrides.CMD_FEED_OVR_COARSE_MINUS);
                break;

            case R.id.feed_overrirde_coarse_pluse:
                sendRealTimeCommand(Overrides.CMD_FEED_OVR_COARSE_PLUS);
                break;

            case R.id.spindle_override_fine_minus:
                sendRealTimeCommand(Overrides.CMD_SPINDLE_OVR_FINE_MINUS);
                break;

            case R.id.spindle_override_fine_plus:
                sendRealTimeCommand(Overrides.CMD_SPINDLE_OVR_FINE_PLUS);
                break;

            case R.id.spindle_override_coarse_minus:
                sendRealTimeCommand(Overrides.CMD_SPINDLE_OVR_COARSE_MINUS);
                break;

            case R.id.spindle_override_coarse_plus:
                sendRealTimeCommand(Overrides.CMD_SPINDLE_OVR_COARSE_PLUS);
                break;

            case R.id.rapid_overrirde_low:
                sendRealTimeCommand(Overrides.CMD_RAPID_OVR_LOW);
                break;

            case R.id.rapid_override_medium:
                sendRealTimeCommand(Overrides.CMD_RAPID_OVR_MEDIUM);
                break;

            case R.id.rapid_overrides_reset:
                sendRealTimeCommand(Overrides.CMD_RAPID_OVR_RESET);
                break;

            case R.id.toggle_spindle:
                sendRealTimeCommand(Overrides.CMD_TOGGLE_SPINDLE);
                break;

            case R.id.toggle_flood_coolant:
                sendRealTimeCommand(Overrides.CMD_TOGGLE_FLOOD_COOLANT);
                break;

            case R.id.toggle_mist_coolant:
                if(machineStatus.getCompileTimeOptions().mistCoolant){
                    sendRealTimeCommand(Overrides.CMD_TOGGLE_MIST_COOLANT);
                }else{
                    EventBus.getDefault().post(new UiToastEvent(getString(R.string.mist_disabled)));
                }
                break;
        }

    }

    private void sendRealTimeCommand(Overrides overrides){
        Byte command = GrblUtils.getOverrideForEnum(overrides);
        if(command != null){
            fragmentInteractionListener.onGrblRealTimeCommandReceived(command);
        }
    }

    private class ReadFileAsyncTask extends AsyncTask<File, Integer, Integer> {

        protected void onPreExecute(){
            fileSender.setRowsInFile(0);
            fileSender.setRowsSent(0);
        }

        protected Integer doInBackground(File... file){
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);

            Integer lines = 0;
            try{
                BufferedReader reader = new BufferedReader(new FileReader(file[0]));
                while (reader.readLine() != null){
                    lines++;
                    if(lines%1000 == 0) publishProgress(lines);
                }
                reader.close();
            }catch (IOException e){
                Log.e(TAG, e.getMessage(), e);
            }

            return lines;
        }

        public void onProgressUpdate(Integer... progress){
            fileSender.setRowsInFile(progress[0]);
        }

        public void onPostExecute(Integer lines){
            fileSender.setRowsInFile(lines);
        }
    }

    private void getFilePicker(){
        String[] gcodeTypes = {".tap",".gcode", ".nc"};
        FilePickerBuilder.getInstance().setMaxCount(1)
                .setActivityTheme(R.style.AppTheme)
                .addFileSupport(".tap | .gcode | .nc", gcodeTypes, R.drawable.ic_insert_drive_file)
                .enableDocSupport(false)
                .pickFile(getActivity());
    }

    private Boolean hasExternalStorageReadPermission(){
        Boolean hasPermission = true;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if(getActivity().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                hasPermission = false;
            }
        }
        return hasPermission;
    }

    private void askExternalReadPermission(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }else{
            EventBus.getDefault().post(new UiToastEvent(getString(R.string.no_external_read_permission)));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if(grantResults[0] == PackageManager.PERMISSION_GRANTED){
            getFilePicker();
        }else{
            EventBus.getDefault().post(new UiToastEvent(getString(R.string.no_external_read_permission)));
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBluetoothDisconnectEvent(BluetoothDisconnectEvent event){
        stopFileStreaming();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGrblErrorEvent(GrblErrorEvent event){
        stopFileStreaming();
    }

}