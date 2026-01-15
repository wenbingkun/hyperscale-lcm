import { DashboardLayout } from './layouts/DashboardLayout';
import { GlassCard } from './components/GlassCard';
import { SatelliteTable } from './components/SatelliteTable';
import { JobSubmissionForm } from './components/JobSubmissionForm';

function App() {
  return (
    <DashboardLayout>
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
        <GlassCard title="Total Nodes">
          <div className="text-4xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 to-blue-500">
            12,450
          </div>
          <div className="text-sm text-gray-400 mt-2">
            <span className="text-green-400">● 12,400 Online</span>
          </div>
        </GlassCard>

        <GlassCard title="GPU Capacity">
          <div className="text-4xl font-bold text-white">
            85 <span className="text-lg text-gray-500">PF</span>
          </div>
          <div className="text-sm text-gray-400 mt-2">
            98,000 A100s
          </div>
        </GlassCard>

        <GlassCard title="Active Jobs">
          <div className="text-4xl font-bold text-white">
            342
          </div>
          <div className="text-sm text-gray-400 mt-2 text-yellow-400">
            ⚡ 85% Utilization
          </div>
        </GlassCard>

        <GlassCard title="Network">
          <div className="text-4xl font-bold text-white">
            4.2 <span className="text-lg text-gray-500">Tbps</span>
          </div>
          <div className="text-sm text-gray-400 mt-2">
            Infiniband Fabric
          </div>
        </GlassCard>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8 mb-8">
        <div className="lg:col-span-2">
          <SatelliteTable />
        </div>
        <div>
          <JobSubmissionForm />
        </div>
      </div>

      <GlassCard title="System Status">
        <div className="h-64 flex items-center justify-center text-gray-500 border border-dashed border-gray-700 rounded">
          Chart Placeholder (Recharts coming soon)
        </div>
      </GlassCard>
    </DashboardLayout>
  );
}

export default App;
