/*
 This file is part of Privacy Friendly To-Do List.

 Privacy Friendly To-Do List is free software:
 you can redistribute it and/or modify it under the terms of the
 GNU General Public License as published by the Free Software Foundation,
 either version 3 of the License, or any later version.

 Privacy Friendly To-Do List is distributed in the hope
 that it will be useful, but WITHOUT ANY WARRANTY; without even
 the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Privacy Friendly To-Do List. If not, see <http://www.gnu.org/licenses/>.
 */

package org.secuso.privacyfriendlytodolist.view;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.support.design.widget.Snackbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.secuso.privacyfriendlytodolist.R;
import org.secuso.privacyfriendlytodolist.model.BaseTodo;
import org.secuso.privacyfriendlytodolist.model.Helper;
import org.secuso.privacyfriendlytodolist.model.TodoSubTask;
import org.secuso.privacyfriendlytodolist.model.TodoTask;
import org.secuso.privacyfriendlytodolist.model.Tuple;
import org.secuso.privacyfriendlytodolist.model.database.DBQueryHandler;
import org.secuso.privacyfriendlytodolist.model.database.DatabaseHelper;
import org.secuso.privacyfriendlytodolist.view.dialog.ProcessTodoSubTaskDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Created by Sebastian Lutz on 06.03.2018
 *
 * This class manages the To-Do task expandableList items.
 */

public class ExpandableTodoTaskAdapter extends BaseExpandableListAdapter {

    private SharedPreferences prefs;

    // left item: task that was long clicked
    // right item: subtask that was long clicked
    private Tuple<TodoTask, TodoSubTask> longClickedTodo;


    public enum Filter {
        ALL_TASKS,
        COMPLETED_TASKS,
        OPEN_TASKS
    }

    public enum SortTypes {
        PRIORITY(0x1),
        DEADLINE(0x2);

        private final int id;

        SortTypes(int id) {
            this.id = id;
        }

        public int getValue() {
            return id;
        }
    }

    // FILTER AND SORTING OPTIONS MADE BY THE USER
    private Filter filterMeasure;
    private String queryString;
    private int sortType = 0; // encodes sorting (1. bit high -> sort by priority, 2. bit high --> sort by deadline)

    // ROW TYPES FOR USED TO CREATE DIFFERENT VIEWS DEPENDING ON ITEM TO SHOW
    private static final int GR_TASK_ROW = 0; // gr == group type
    private static final int GR_PRIO_ROW = 1;
    private static final int CH_TASK_DESCRIPTION_ROW = 0; // ch == child type
    private static final int CH_SETTING_ROW = 1;
    private static final int CH_SUBTASK_ROW = 2;

    // DATA TO DISPLAY
    private ArrayList<TodoTask> rawData; // data from database in original order
    private ArrayList<TodoTask> filteredTasks = new ArrayList<>(); // data after filtering process

    // OTHERS
    private Context context;
    private HashMap<TodoTask.Priority, Integer> prioBarPositions = new HashMap<>();

    // Normally the toolbar title contains the list name. However, it all tasks are displayed in a dummy list it is not obvious to what list a tasks belongs. This missing information is then added to each task in an additional text view.
    private boolean showListName = false;

    public ExpandableTodoTaskAdapter(Context context, ArrayList<TodoTask> tasks) {
        this.context = context;

        prefs = PreferenceManager.getDefaultSharedPreferences(context);

        rawData = tasks;

        // default values
        setFilter(Filter.ALL_TASKS);
        setQueryString(null);
        filterTasks();
    }

    public void setLongClickedTaskByPos(int position) {
        longClickedTodo = Tuple.makePair(getTaskByPosition(position), null);
    }

    public void setListNames(boolean flag) {
        showListName = flag;
    }

    public void setLongClickedSubTaskByPos(int groupPosition, int childPosition) {
        TodoTask task = getTaskByPosition(groupPosition);
        if(task!=null) {
            TodoSubTask subTask = task.getSubTasks().get(childPosition - 1);
            longClickedTodo = Tuple.makePair(task, subTask);
        }
    }

    public Tuple<TodoTask, TodoSubTask> getLongClickedTodo() {
        return longClickedTodo;
    }

    // interface to outer world
    public void setFilter(Filter filter) {
        this.filterMeasure = filter;
    }

    public void setQueryString(String query) {
        this.queryString = query;
    }

    /**
     * Sets the n-th bit of {@link ExpandableTodoTaskAdapter#sortType} whereas n is the value of {@param type}
     * After having changed the sorting conditions, you must call {@link ExpandableTodoTaskAdapter#sortTasks}
     *
     * @param type condition by what tasks will be sorted (one-hot encoding)
     */
    public void addSortCondition(SortTypes type) {
        this.sortType |= type.getValue(); // set n-th bit
    }

    /**
     * Sets the n-th bit of {@link ExpandableTodoTaskAdapter#sortType} whereas n is the value of {@param type}
     * After having changed the sorting conditions, you must call {@link ExpandableTodoTaskAdapter#sortTasks}
     *
     * @param type condition by what tasks will be sorted (one-hot encoding)
     */
    public void removeSortCondition(SortTypes type) {
        this.sortType &= ~(1 << type.getValue() - 1);
    }

    /**
     * filter tasks by "done" criterion (show "all", only "open" or only "completed" tasks)
     * If the user changes the filter, it is crucial to call "sortTasks" again.
     **/
    private void filterTasks() {
        filteredTasks.clear();

        boolean notOpen = filterMeasure != Filter.OPEN_TASKS;
        boolean notCompleted = filterMeasure != Filter.COMPLETED_TASKS;

        for (TodoTask task : rawData)
            if ((notOpen && task.getDone()) || (notCompleted && !task.getDone()))
                if(task.checkQueryMatch(this.queryString))
                    filteredTasks.add(task);

        // Call this method even if sorting is disabled. In the case of enabled sorting, all
        // sorting patterns are automatically employed after having changed the filter on tasks.
        sortTasks();
    }

    private boolean isPriorityGroupingEnabled() {
        return (sortType & SortTypes.PRIORITY.getValue()) == 1;
    }

    /**
     * Sort tasks by selected criteria (priority and/or deadline)
     * This method works on {@link ExpandableTodoTaskAdapter#filteredTasks}. For that reason it is
     * important to keep {@link ExpandableTodoTaskAdapter#filteredTasks} up-to-date.
     **/
    public void sortTasks() {

        final boolean prioSorting = isPriorityGroupingEnabled();
        final boolean deadlineSorting = (sortType & SortTypes.DEADLINE.getValue()) != 0;

        Collections.sort(filteredTasks, new Comparator<TodoTask>() {

            private int compareDeadlines(long d1, long d2) {
                // tasks with deadlines always first
                if (d1 == -1 && d2 == -1) return 0;
                if (d1 == -1) return 1;
                if (d2 == -1) return -1;

                if (d1 < d2) return -1;
                if (d1 == d2) return 0;
                return 1;
            }

            @Override
            public int compare(TodoTask t1, TodoTask t2) {

                if (prioSorting) {
                    TodoTask.Priority p1 = t1.getPriority();
                    TodoTask.Priority p2 = t2.getPriority();
                    int comp = p1.compareTo(p2);

                    if (comp == 0 && deadlineSorting) {
                        return compareDeadlines(t1.getDeadline(), t2.getDeadline());
                    }
                    return comp;

                } else if (deadlineSorting) {
                    return compareDeadlines(t1.getDeadline(), t2.getDeadline());
                } else
                    return t1.getListPosition() - t2.getListPosition();

            }
        });

        if (prioSorting)
            countTasksPerPriority();

    }

    // count how many tasks belong to each priority group (tasks are now sorted by priority)

    /**
     * If {@link ExpandableTodoTaskAdapter#sortTasks()} sorted by the priority, this method must be
     * called. It computes the position of the dividing bars between the priority ranges. These
     * positions are necessary to distinguish of what group type the current row is.
     */
    private void countTasksPerPriority() {

        prioBarPositions.clear();
        if (filteredTasks.size() != 0) {

            int pos = 0;
            TodoTask.Priority currentPrio;
            HashSet<TodoTask.Priority> prioAlreadySeen = new HashSet<>();
            for (TodoTask task : filteredTasks) {
                currentPrio = task.getPriority();
                if (!prioAlreadySeen.contains(currentPrio)) {
                    prioAlreadySeen.add(currentPrio);
                    prioBarPositions.put(currentPrio, pos);
                    pos++; // skip the current prio-line
                }
                pos++;
            }
        }
    }

    /***
     * @param groupPosition position of current row. For that reason the offset to the task must be
     *                      computed taking into account all preceding dividing priority bars
     * @return null if there is no task at @param groupPosition (but a divider row) or the wanted task
     */

    private TodoTask getTaskByPosition(int groupPosition) {

        int seenPrioBars = 0;
        if (isPriorityGroupingEnabled()) {
            for (TodoTask.Priority priority : TodoTask.Priority.values()) {
                if (prioBarPositions.containsKey(priority)) {
                    if (groupPosition < prioBarPositions.get(priority))
                        break;
                    seenPrioBars++;
                }
            }
        }

        int pos = groupPosition - seenPrioBars;

        if (pos < filteredTasks.size() && pos >= 0)
            return filteredTasks.get(pos);

        return null; // should never be the case
    }

    @Override
    public int getGroupCount() {
        if (isPriorityGroupingEnabled())
            return filteredTasks.size() + prioBarPositions.size();
        else
            return filteredTasks.size();
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        TodoTask task = getTaskByPosition(groupPosition);
        if(task == null)
            return 0;
        return task.getSubTasks().size() + 2;
    }

    @Override
    public int getGroupType(int groupPosition) {

        if (isPriorityGroupingEnabled() && prioBarPositions.values().contains(groupPosition))
            return GR_PRIO_ROW;
        return GR_TASK_ROW;
    }

    @Override
    public int getGroupTypeCount() {
        return 2;
    }

    @Override
    public int getChildType(int groupPosition, int childPosition) {
        if (childPosition == 0)
            return CH_TASK_DESCRIPTION_ROW;
        else if (childPosition == getTaskByPosition(groupPosition).getSubTasks().size() + 1)
            return CH_SETTING_ROW;
        return CH_SUBTASK_ROW;
    }

    @Override
    public int getChildTypeCount() {
        return 3;
    }

    @Override
    public Object getGroup(int groupPosition) {
        return filteredTasks.get(groupPosition);
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        return childPosition;
    }

    @Override
    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    private String getPriorityNameByBarPos(int groupPosition) {
        for (Map.Entry<TodoTask.Priority, Integer> entry : prioBarPositions.entrySet()) {
            if (entry.getValue() == groupPosition) {
                return Helper.priority2String(context, entry.getKey());
            }
        }
        return context.getString(R.string.unknown_priority);
    }

    @Override
    public void notifyDataSetChanged() {
        filterTasks();
        super.notifyDataSetChanged();
    }

    private long getDefaultReminderTime()  {
        return new Long(prefs.getString(Settings.DEFAULT_REMINDER_TIME_KEY, String.valueOf(context.getResources().getInteger(R.integer.one_day))));
    }

    @Override
    public View getGroupView(final int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {


        int type = getGroupType(groupPosition);

        switch (type) {

            case GR_PRIO_ROW:

                GroupPrioViewHolder vh1;

                if (convertView == null) {
                    convertView = LayoutInflater.from(context).inflate(R.layout.exlv_prio_bar, parent, false);
                    vh1 = new GroupPrioViewHolder();
                    vh1.prioFlag = (TextView) convertView.findViewById(R.id.tv_exlv_priority_bar);
                    convertView.setTag(vh1);
                } else {
                    vh1 = (GroupPrioViewHolder) convertView.getTag();
                }

                vh1.prioFlag.setText(getPriorityNameByBarPos(groupPosition));
                convertView.setClickable(true);

                break;

            case GR_TASK_ROW:

                final TodoTask currentTask = getTaskByPosition(groupPosition);
                final TodoSubTask currentSubTask;
                final GroupTaskViewHolder vh2;

                if (convertView == null) {

                    convertView = LayoutInflater.from(context).inflate(R.layout.exlv_tasks_group, parent, false);

                    vh2 = new GroupTaskViewHolder();
                    vh2.name = (TextView) convertView.findViewById(R.id.tv_exlv_task_name);
                    vh2.done = (CheckBox) convertView.findViewById(R.id.cb_task_done);
                    vh2.deadline = (TextView) convertView.findViewById(R.id.tv_exlv_task_deadline);
                    vh2.listName = (TextView) convertView.findViewById(R.id.tv_exlv_task_list_name);
                    vh2.progressBar = (ProgressBar) convertView.findViewById(R.id.pb_task_progress);
                    vh2.seperator = convertView.findViewById(R.id.v_exlv_header_separator);
                    vh2.deadlineColorBar = convertView.findViewById(R.id.v_urgency_task);
                    vh2.done.setTag(currentTask.getId());
                    vh2.done.setChecked(currentTask.getDone());

                    convertView.setTag(vh2);

                } else {
                    vh2 = (GroupTaskViewHolder) convertView.getTag();
                }

                vh2.name.setText(currentTask.getName());
                getProgressDone(currentTask, hasAutoProgress());
                vh2.progressBar.setProgress(currentTask.getProgress());
                String deadline;
                if (currentTask.getDeadline() <= 0)
                    deadline = context.getResources().getString(R.string.no_deadline);
                else
                    deadline = context.getResources().getString(R.string.deadline_dd) + " " + Helper.getDate(currentTask.getDeadline());

                if(showListName) {
                    vh2.listName.setVisibility(View.VISIBLE);
                    vh2.listName.setText(currentTask.getListName());
                } else {
                    vh2.listName.setVisibility(View.GONE);
                }

                vh2.deadline.setText(deadline);
                vh2.deadlineColorBar.setBackgroundColor(Helper.getDeadlineColor(context, currentTask.getDeadlineColor(getDefaultReminderTime())));
                vh2.done.setChecked(currentTask.getDone());
                vh2.done.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {

                        if(buttonView.isPressed()) {
                            Snackbar snackbar = Snackbar.make(buttonView, R.string.snack_check, Snackbar.LENGTH_LONG);
                            snackbar.setAction(R.string.snack_undo, new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (isChecked){
                                        buttonView.setChecked(false);
                                        currentTask.setDone(buttonView.isChecked());
                                        currentTask.setAllSubTasksDone(false);
                                        getProgressDone(currentTask, hasAutoProgress());
                                        currentTask.setChanged();
                                        notifyDataSetChanged();
                                        DBQueryHandler.saveTodoTaskInDb(DatabaseHelper.getInstance(context).getWritableDatabase(), currentTask);
                                        for (TodoSubTask st : currentTask.getSubTasks()){
                                            st.setDone(false);
                                            DBQueryHandler.saveTodoSubTaskInDb(DatabaseHelper.getInstance(context).getWritableDatabase(), st);
                                        }
                                    } else {
                                        buttonView.setChecked(true);
                                        currentTask.setDone(buttonView.isChecked());
                                        currentTask.setAllSubTasksDone(true);
                                        getProgressDone(currentTask, hasAutoProgress());
                                        currentTask.setChanged();
                                        notifyDataSetChanged();
                                        DBQueryHandler.saveTodoTaskInDb(DatabaseHelper.getInstance(context).getWritableDatabase(), currentTask);
                                        for (TodoSubTask st : currentTask.getSubTasks()){
                                            st.setDone(true);
                                            DBQueryHandler.saveTodoSubTaskInDb(DatabaseHelper.getInstance(context).getWritableDatabase(), st);
                                        }
                                    }

                                }
                            });
                            snackbar.show();
                            currentTask.setDone(buttonView.isChecked());
                            currentTask.setAllSubTasksDone(buttonView.isChecked());
                            getProgressDone(currentTask, hasAutoProgress());
                            currentTask.setChanged();
                            notifyDataSetChanged();
                            DBQueryHandler.saveTodoTaskInDb(DatabaseHelper.getInstance(context).getWritableDatabase(), currentTask);
                            for (int i=0; i < currentTask.getSubTasks().size(); i++) {
                                currentTask.getSubTasks().get(i).setChanged();
                                notifyDataSetChanged();
                            }
                        }
                    }
                });

                break;
            default:
                // TODO Exception
        }

        return convertView;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {

        int type = getChildType(groupPosition, childPosition);
        final TodoTask currentTask = getTaskByPosition(groupPosition);

        switch (type) {
            case CH_TASK_DESCRIPTION_ROW:

                TaskDescriptionViewHolder vh1 = new TaskDescriptionViewHolder();
                if (convertView == null) {
                    convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.exlv_task_description_row, parent, false);
                    vh1.taskDescription = (TextView) convertView.findViewById(R.id.tv_exlv_task_description);
                    vh1.deadlineColorBar = convertView.findViewById(R.id.v_task_description_deadline_color_bar);
                    convertView.setTag(vh1);
                } else {
                    vh1 = (TaskDescriptionViewHolder) convertView.getTag();
                }

                String description = currentTask.getDescription();
                if (description != null && !description.equals("")) {
                    vh1.taskDescription.setVisibility(View.VISIBLE);
                    vh1.taskDescription.setText(description);
                }
                else {
                    vh1.taskDescription.setVisibility(View.GONE);
                    // vh1.taskDescription.setText("KEINE BESCHREIBUNG"); //context.getString(R.string.no_task_description));
                }
                vh1.deadlineColorBar.setBackgroundColor(Helper.getDeadlineColor(context, currentTask.getDeadlineColor(getDefaultReminderTime())));

                break;

            case CH_SETTING_ROW:
                SettingViewHolder vh2 = new SettingViewHolder();
                if (convertView == null) {
                    convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.exlv_setting_row, parent, false);
                    //vh2.addSubTaskButton = (ImageView) convertView.findViewById(R.id.iv_add_subtask);
                    vh2.addSubTaskButton = (RelativeLayout) convertView.findViewById(R.id.rl_add_subtask);
                    vh2.deadlineColorBar = convertView.findViewById(R.id.v_setting_deadline_color_bar);
                    convertView.setTag(vh2);
                    if (currentTask.isInTrash())
                        convertView.setVisibility(View.GONE);
                } else {
                    vh2 = (SettingViewHolder) convertView.getTag();
                }

                vh2.addSubTaskButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                    ProcessTodoSubTaskDialog dialog = new ProcessTodoSubTaskDialog(context);
                    dialog.setDialogResult(new TodoCallback() {
                        @Override
                        public void finish(BaseTodo b) {
                            if(b instanceof TodoSubTask) {
                                TodoSubTask newSubTask = (TodoSubTask) b;
                                currentTask.getSubTasks().add(newSubTask);
                                newSubTask.setTaskId(currentTask.getId());
                                DBQueryHandler.saveTodoSubTaskInDb(DatabaseHelper.getInstance(context).getWritableDatabase(), newSubTask);
                                notifyDataSetChanged();
                            }
                        }
                    });
                    dialog.show();
                    }
                });
                vh2.deadlineColorBar.setBackgroundColor(Helper.getDeadlineColor(context, currentTask.getDeadlineColor(getDefaultReminderTime())));

                break;
            default:
                final TodoSubTask currentSubTask = currentTask.getSubTasks().get(childPosition - 1);
                SubTaskViewHolder vh3 = new SubTaskViewHolder();
                if (convertView == null) {
                    convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.exlv_subtask_row, parent, false);
                    vh3.subtaskName = (TextView) convertView.findViewById(R.id.tv_subtask_name);
                    vh3.deadlineColorBar = convertView.findViewById(R.id.v_subtask_deadline_color_bar);
                    vh3.done = (CheckBox) convertView.findViewById(R.id.cb_subtask_done);
                    convertView.setTag(vh3);
                } else {
                    vh3 = (SubTaskViewHolder) convertView.getTag();
                }

                vh3.done.setChecked(currentSubTask.getDone());
                vh3.done.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                        if(buttonView.isPressed()) {
                            currentSubTask.setDone(buttonView.isChecked());
                            currentTask.doneStatusChanged(); // check if entire task is now (when all subtasks are done)
                            currentSubTask.setChanged();
                            DBQueryHandler.saveTodoSubTaskInDb(DatabaseHelper.getInstance(context).getWritableDatabase(), currentSubTask);
                            getProgressDone(currentTask, hasAutoProgress());
                            DBQueryHandler.saveTodoTaskInDb(DatabaseHelper.getInstance(context).getWritableDatabase(), currentTask);
                            notifyDataSetChanged();
                        }
                    }
                });
                vh3.subtaskName.setText(currentSubTask.getName());
                vh3.deadlineColorBar.setBackgroundColor(Helper.getDeadlineColor(context, currentTask.getDeadlineColor(getDefaultReminderTime())));

        }
        return convertView;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return childPosition > 0 && childPosition < getTaskByPosition(groupPosition).getSubTasks().size() + 1;
    }

    public void getProgressDone(TodoTask t, boolean autoProgress) {
        if (autoProgress) {
            int progress = 0;
            int help = 0;
            ArrayList<TodoSubTask> subs = t.getSubTasks();
            for (TodoSubTask st : subs){
                if (st.getDone()){
                    help++;
                }
            }
            double computedProgress = ((double)help/(double)t.getSubTasks().size())*100;
            progress = (int) computedProgress;
            t.setProgress(progress);
        } else
        t.setProgress(t.getProgress());
    }

    public class GroupTaskViewHolder {
        public TextView name;
        public TextView deadline;
        public TextView listName;
        public CheckBox done;
        public View deadlineColorBar;
        public View seperator;
        public ProgressBar progressBar;
    }

    public class GroupPrioViewHolder {
        public TextView prioFlag;
    }

    private class SubTaskViewHolder {
        public TextView subtaskName;
        public CheckBox done;
        public View deadlineColorBar;
    }

    private class TaskDescriptionViewHolder {
        public TextView taskDescription;
        public View deadlineColorBar;
    }

    private class SettingViewHolder {
        public RelativeLayout addSubTaskButton;
        public View deadlineColorBar;
    }

    private boolean hasAutoProgress() {
        //automatic-progress enabled?
        if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean("pref_progress", false))
            return false;
        return true;
    }


}