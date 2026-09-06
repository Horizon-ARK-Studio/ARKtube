project('cage', 'c',
  version: '0.3.0',
  license: 'MIT',
  meson_version: '>=0.58.1',
  default_options: [
    'c_std=c11',
    'warning_level=2',
    'werror=true',
  ],
)

add_project_arguments(
  [
    '-DWLR_USE_UNSTABLE',
    '-Wundef',
    '-Wno-unused-parameter',
  ],
  language: 'c',
)

fs = import('fs')

if get_option('buildtype').startswith('debug')
  add_project_arguments('-DDEBUG', language : 'c')
endif

cc = meson.get_compiler('c')

is_freebsd = host_machine.system().startswith('freebsd')
if is_freebsd
  add_project_arguments(
    [
      '-Wno-format-extra-args',
      '-Wno-gnu-zero-variadic-macro-arguments',
    ],
    language: 'c'
  )
endif

wlroots        = dependency('wlroots-0.20', fallback: ['wlroots', 'wlroots'])
wayland_server = dependency('wayland-server')
xkbcommon      = dependency('xkbcommon')
math           = cc.find_library('m')

have_xwayland = wlroots.get_variable(pkgconfig: 'have_xwayland', internal: 'have_xwayland') == 'true'

# wlr-layer-shell-unstable-v1: wlroots' own wlr_layer_shell_v1.h includes a
# generated "wlr-layer-shell-unstable-v1-protocol.h" that its -dev package
# does not actually install (confirmed against Ubuntu Noble's
# libwlroots-dev, whose pkg-config Cflags only exposes ${includedir} —
# nothing protocol-generated). wlroots expects consumers of unstable
# protocols to generate that header themselves from the upstream XML, the
# same way sway/dwl/labwc each vendor it rather than assume it's installed.
# The XML itself (protocol/wlr-layer-shell-unstable-v1.xml, vendored in
# this tree) is byte-for-byte from swaywm/wlr-protocols@master, the same
# copy wlroots itself builds against.
wayland_scanner_dep = dependency('wayland-scanner', native: true)
# Positional (type-agnostic) form, not `pkgconfig: 'wayland_scanner'`.
# On a system where wayland-server is new enough to be found via
# pkg-config, wayland-scanner resolves the same way and either form
# would work. But when wayland-server is too old (as on Ubuntu Noble,
# where apt's 1.22.0 doesn't meet wlroots-0.20's own >=1.24.0 floor --
# see subprojects/wlroots.wrap), meson falls back to building
# subprojects/wayland from source, which overrides the global
# wayland-scanner dependency with an *internal* (non-pkgconfig)
# dependency object. Asking an internal dependency for a pkgconfig
# variable throws "Could not get an internal variable and no default
# provided". The positional form auto-detects the dependency's actual
# type and asks it correctly either way -- this is exactly how
# wlroots' own meson.build (examples.meson.build) resolves the same
# wayland-scanner lookup.
wayland_scanner = find_program(
  wayland_scanner_dep.get_variable('wayland_scanner'),
  native: true,
)

layer_shell_xml = join_paths(meson.current_source_dir(), 'protocol', 'wlr-layer-shell-unstable-v1.xml')

layer_shell_server_header = custom_target(
  'wlr-layer-shell-unstable-v1-protocol.h',
  input: layer_shell_xml,
  output: 'wlr-layer-shell-unstable-v1-protocol.h',
  command: [wayland_scanner, 'server-header', '@INPUT@', '@OUTPUT@'],
)

layer_shell_protocol_code = custom_target(
  'wlr-layer-shell-unstable-v1-protocol.c',
  input: layer_shell_xml,
  output: 'wlr-layer-shell-unstable-v1-protocol.c',
  command: [wayland_scanner, 'private-code', '@INPUT@', '@OUTPUT@'],
)

version = '@0@'.format(meson.project_version())
if fs.is_dir('.git')
  git = find_program('git', native: true, required: false)
  if git.found()
    git_commit = run_command([git, 'rev-parse', '--short', 'HEAD'], check: false)
    git_branch = run_command([git, 'rev-parse', '--abbrev-ref', 'HEAD'], check: false)
    if git_commit.returncode() == 0 and git_branch.returncode() == 0
      version = '@0@-@1@ (branch \'@2@\')'.format(
        meson.project_version(),
        git_commit.stdout().strip(),
        git_branch.stdout().strip(),
      )
    endif
  endif
endif

conf_data = configuration_data()
conf_data.set10('CAGE_HAS_XWAYLAND', have_xwayland)
conf_data.set_quoted('CAGE_VERSION', version)

scdoc = dependency('scdoc', version: '>=1.9.2', native: true, required: get_option('man-pages'))
if scdoc.found()
  scdoc_prog = find_program(scdoc.get_variable('scdoc'), native: true)
  sh = find_program('sh', native: true)
  mandir = get_option('mandir')
  man_files = [
    'cage.1.scd'
  ]
  foreach filename : man_files
    topic = filename.split('.')[-3].split('/')[-1]
    section = filename.split('.')[-2]
    output = '@0@.@1@'.format(topic, section)

    custom_target(
      output,
      input: filename,
      output: output,
      command: [
        sh, '-c', '@0@ < @INPUT@ > @1@'.format(scdoc_prog.full_path(), output)
      ],
      install: true,
      install_dir: '@0@/man@1@'.format(mandir, section)
    )
  endforeach
endif

cage_sources = [
  'cage.c',
  'idle_inhibit_v1.c',
  'layer_shell.c',
  'output.c',
  'seat.c',
  'view.c',
  'xdg_shell.c',
  layer_shell_server_header,
  layer_shell_protocol_code,
  configure_file(input: 'config.h.in',
                 output: 'config.h',
                 configuration: conf_data),
]

if conf_data.get('CAGE_HAS_XWAYLAND', 0) == 1
  cage_sources += 'xwayland.c'
endif

executable(
  meson.project_name(),
  cage_sources,
  dependencies: [
    wayland_server,
    wlroots,
    xkbcommon,
    math,
  ],
  install: true,
)

summary = [
  '',
  'Cage @0@'.format(version),
  '',
  '    xwayland: @0@'.format(have_xwayland),
  ''
]
message('\n'.join(summary))
