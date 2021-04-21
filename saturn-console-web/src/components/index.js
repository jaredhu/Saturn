import Vue from 'vue';
import Container from './common/container';
import VHeader from './common/header';
import topBar from './common/top_bar';
import Breadcrumb from './common/breadcrumb';
import VAside from './common/aside';
import Timeline from './common/timeline';
import TimelineItem from './common/timeline_item';
import ChartContainer from './common/charts/chart_container';
import Pie from './common/charts/pie';
import MyLine from './common/charts/line';
import Column from './common/charts/column';
import Relate from './common/charts/relate';
import Panel from './common/panel';
import CardPanel from './common/cardPanel';
import FilterPageList from './common/filterPageList';
import ImportFileDialog from './common/importFileDialog';
import ViewContentDialog from './common/viewContentDialog';
import AbnormalJobs from './common/abnormalJobs';
import TimeoutJobs from './common/timeoutJobs';
import UnableFailoverJobs from './common/unableFailoverJobs';
import AbnormalContainers from './common/abnormalContainers';
import CronPredictDialog from './common/cronPredictDialog';
import InputTags from './common/inputTags';
import BatchMigrateDialog from './common/dialog/batch_migrate_dialog';
import MigrateStatusDialog from './common/dialog/migrate_status_dialog';
import AddConfigDialog from './common/dialog/add_config_dialog';
import JobInfoDialog from './common/dialog/job_info_dialog';
import BatchPriorityDialog from './/common/dialog/batch_priority_dialog';
import ImportResultDialog from './common/dialog/import_result_dialog';
import ArrangeLayoutDialog from './common/dialog/arrange_layout_dialog';
import JobGroupDialog from './common/dialog/job_group_dialog';

Vue.component('Container', Container);
Vue.component('VHeader', VHeader);
Vue.component('Top-bar', topBar);
Vue.component('Breadcrumb', Breadcrumb);
Vue.component('VAside', VAside);
Vue.component('Timeline', Timeline);
Vue.component('Timeline-item', TimelineItem);
Vue.component('Chart-container', ChartContainer);
Vue.component('Pie', Pie);
Vue.component('Column', Column);
Vue.component('MyLine', MyLine);
Vue.component('Relate', Relate);
Vue.component('Panel', Panel);
Vue.component('CardPanel', CardPanel);
Vue.component('FilterPageList', FilterPageList);
Vue.component('ImportFileDialog', ImportFileDialog);
Vue.component('ViewContentDialog', ViewContentDialog);
Vue.component('AbnormalJobs', AbnormalJobs);
Vue.component('TimeoutJobs', TimeoutJobs);
Vue.component('UnableFailoverJobs', UnableFailoverJobs);
Vue.component('AbnormalContainers', AbnormalContainers);
Vue.component('CronPredictDialog', CronPredictDialog);
Vue.component('InputTags', InputTags);
Vue.component('batch-migrate-dialog', BatchMigrateDialog);
Vue.component('migrate-status-dialog', MigrateStatusDialog);
Vue.component('add-config-dialog', AddConfigDialog);
Vue.component('job-info-dialog', JobInfoDialog);
Vue.component('batch-priority-dialog', BatchPriorityDialog);
Vue.component('import-result-dialog', ImportResultDialog);
Vue.component('arrange-layout-dialog', ArrangeLayoutDialog);
Vue.component('job-group-dialog', JobGroupDialog);
